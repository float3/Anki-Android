/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import anki.collection.OpChanges
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Shows XP, combo, quest and level feedback from an ankiquest server after each answer.
 *
 * With a token, new review log rows are uploaded to the server. Without one, they are only
 * sent to its preview endpoint, which stores nothing.
 */
object Ankiquest : ChangeManager.Subscriber, Application.ActivityLifecycleCallbacks {
    const val URL_KEY = "ankiquestUrl"
    const val USER_KEY = "ankiquestUser"
    const val TOKEN_KEY = "ankiquestToken"
    private const val MARK_KEY = "ankiquestUploadedThrough"
    private const val RECENT_KEY = "ankiquestRecentUploads"
    private const val INITIAL_SYNC_KEY = "ankiquestInitialSyncDone"

    private const val MAX_PENDING = 5000
    private const val BASELINE_MAX_AGE_MS = 10 * 60 * 1000L
    private const val RESUME_UPLOAD_INTERVAL_MS = 60 * 1000L
    private const val RESYNC_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    private const val UNDO_WINDOW_MS = 2 * 24 * 60 * 60 * 1000L
    private const val BANNER_MS = 1800L
    private const val BANNER_LONG_MS = 3500L
    private const val BANNER_TAG = "ankiquest_banner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    private val json = "application/json".toMediaType()

    private var app: Application? = null
    private var activity = WeakReference<Activity>(null)
    private var syncedLastId = 0L
    private var baselineAt = 0L
    private var resumeUploadAt = 0L
    private var previous: Snapshot? = null

    private data class Snapshot(
        val xp: Long,
        val level: Int,
        val intoLevel: Long,
        val forNext: Long,
        val streak: Int,
        val combo: Int,
        val doneQuests: Set<String>,
        val achievements: Set<String>,
    )

    fun init(app: Application) {
        this.app = app
        app.registerActivityLifecycleCallbacks(this)
        ChangeManager.subscribe(this, owner = null)
        AnkiquestPoll.schedule(app)
    }

    /** The player's profile, as served by `/api/profile/<user>`. */
    suspend fun profile(): JSONObject =
        withContext(Dispatchers.IO) {
            val (url, user) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            get("$url/api/profile/$user")
        }

    /** The configured player name, or the sync username when none is set. */
    fun player(): String? =
        AnkiDroidApp
            .sharedPrefs()
            .getString(USER_KEY, "")
            .orEmpty()
            .trim()
            .ifEmpty { Prefs.username.orEmpty() }
            .ifEmpty { null }

    private fun endpoint(): Pair<String, String>? {
        val url =
            AnkiDroidApp
                .sharedPrefs()
                .getString(URL_KEY, "")
                .orEmpty()
                .trim()
                .trimEnd('/')
        val user = player()
        if (url.isEmpty() || user == null) return null
        return url to URLEncoder.encode(user, "UTF-8").replace("+", "%20")
    }

    /** This week's standings, as served by `/api/leaderboard`. */
    suspend fun leaderboard(): JSONArray =
        withContext(Dispatchers.IO) {
            val (url, _) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            client.newCall(Request.Builder().url("$url/api/leaderboard").build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                JSONArray(response.body.string())
            }
        }

    /** Silently refresh the complete local catalog before loading private preferences. */
    suspend fun deckNotificationSettings(): JSONObject =
        withContext(Dispatchers.IO) {
            val (url, user, token) = authenticatedEndpoint()
            mutex.withLock {
                upload(url, user, token, resync = false, catalog = true)
                val local =
                    CollectionManager.withCol {
                        decks.allNamesAndIds(includeFiltered = false).map { it.id.toString() }.toSet()
                    }
                fetchDeckNotificationSettings(url, user, token).also { settings ->
                    settings.put("decks", JSONArray(settings.getJSONArray("decks").objects().filter { it.getString("id") in local }))
                }
            }
        }

    private fun fetchDeckNotificationSettings(
        url: String,
        user: String,
        token: String,
    ): JSONObject =
        execute(
            Request
                .Builder()
                .url("$url/api/decks/$user")
                .header("Authorization", "Bearer $token")
                .build(),
        )

    suspend fun saveDeckNotificationSettings(
        id: String,
        enabled: Boolean,
        recipients: List<String>,
    ) = withContext(Dispatchers.IO) {
        val (url, user, token) = authenticatedEndpoint()
        val deck = JSONObject().put("id", id).put("enabled", enabled).put("recipients", JSONArray(recipients))
        execute(
            Request
                .Builder()
                .url("$url/api/decks/$user")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("decks", JSONArray().put(deck)).toString().toRequestBody(json))
                .build(),
        )
    }

    /** The account key accompanies the response so a settings change cannot mix inbox cursors. */
    suspend fun completionNotifications(): Pair<String, JSONArray>? =
        withContext(Dispatchers.IO) {
            if (AnkiDroidApp.sharedPrefs().getString(TOKEN_KEY, "").isNullOrBlank()) return@withContext null
            val (url, user, token) = authenticatedEndpoint()
            client
                .newCall(
                    Request
                        .Builder()
                        .url("$url/api/notifications/$user")
                        .header("Authorization", "Bearer $token")
                        .build(),
                ).execute()
                .use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    "$url/$user" to JSONArray(response.body.string())
                }
        }

    private fun authenticatedEndpoint(): Triple<String, String, String> {
        val (url, user) = endpoint() ?: throw IllegalStateException("Set the server URL and player first.")
        val token =
            AnkiDroidApp
                .sharedPrefs()
                .getString(TOKEN_KEY, "")
                .orEmpty()
                .trim()
        check(token.isNotEmpty()) { "Set your ankiquest token to manage deck notifications." }
        return Triple(url, user, token)
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        if (!changes.studyQueues) return
        val reviewing = handler is Reviewer || handler is ReviewerViewModel || isReviewing(activity.get())
        if (!reviewing && handler != null) return
        launchRefresh(showFeedback = reviewing)
    }

    private fun isReviewing(host: Activity?): Boolean =
        host is Reviewer ||
            (host as? FragmentActivity)?.supportFragmentManager?.fragments?.any { it is ReviewerFragment } == true

    private fun launchRefresh(
        showFeedback: Boolean,
        resync: Boolean = false,
    ) {
        val (url, user) = endpoint() ?: return
        val token =
            AnkiDroidApp
                .sharedPrefs()
                .getString(TOKEN_KEY, "")
                .orEmpty()
                .trim()
        scope.launch {
            try {
                mutex.withLock {
                    val profile = if (token.isEmpty()) preview(url, user) else upload(url, user, token, resync)
                    present(profile, showFeedback)
                }
            } catch (e: Exception) {
                Timber.w(e, "ankiquest refresh failed")
            }
        }
    }

    private suspend fun preview(
        url: String,
        user: String,
    ): JSONObject {
        val now = TimeManager.time.intTimeMS()
        if (now - baselineAt > BASELINE_MAX_AGE_MS) {
            syncedLastId = get("$url/api/profile/$user").optLong("last_review_id")
            baselineAt = now
        }
        val body = JSONObject().put("reviews", pendingReviews(syncedLastId))
        return execute(
            Request
                .Builder()
                .url("$url/api/preview/$user")
                .post(body.toString().toRequestBody(json))
                .build(),
        )
    }

    private suspend fun upload(
        url: String,
        user: String,
        token: String,
        resync: Boolean,
        onlyTest: Boolean = false,
        catalog: Boolean = false,
    ): JSONObject {
        val prefs = AnkiDroidApp.sharedPrefs()
        val mark = prefs.getLong(MARK_KEY, 0L)
        val initialSyncKey = "$INITIAL_SYNC_KEY:$url/$user"
        val initialSyncDone = prefs.getBoolean(initialSyncKey, false)
        var known = if (resync) maxOf(0L, mark - RESYNC_WINDOW_MS) else mark
        val clock = clock()

        val windowStart = TimeManager.time.intTimeMS() - UNDO_WINDOW_MS
        val recent = recentUploads(prefs, windowStart)
        val window = if (onlyTest) emptyList() else pendingReviews(windowStart).objects()
        val present = window.map { it.getLong("id") }.toSet()
        val deleted = if (onlyTest || mark == 0L) emptySet() else recent - present
        val restored = window.filter { it.getLong("id") <= known && it.getLong("id") !in recent }

        var profile: JSONObject
        var first = true
        do {
            val batch = if (onlyTest) JSONArray() else pendingReviews(known)
            val full = batch.length() == MAX_PENDING
            if (batch.length() > 0) known = batch.getJSONObject(batch.length() - 1).getLong("id")
            val body =
                JSONObject()
                    .put("clock", clock)
                    .put("silent", catalog || AnkiquestCompletionPolicy.silentUpload(mark, initialSyncDone, full, onlyTest))
            if (first) {
                restored.forEach { batch.put(it) }
                body.put("deleted", JSONArray(deleted.toList()))
            }
            body.put("reviews", batch)
            // Only the final batch represents complete progress; connection checks are not study.
            if (!full && !onlyTest) {
                runCatching {
                    if (catalog) {
                        deckSnapshots(clock)
                    } else {
                        // Fetch for this captured account each time so changed or revoked sharing is respected.
                        val enabled =
                            fetchDeckNotificationSettings(url, user, token)
                                .getJSONArray("decks")
                                .objects()
                                .filter { it.getBoolean("enabled") }
                                .map { it.getString("id") }
                                .toSet()
                        if (enabled.isEmpty()) null else JSONArray(deckSnapshots(clock).objects().filter { it.getString("id") in enabled })
                    }
                }.onSuccess { decks ->
                    if (decks != null) body.put("decks", decks).put("catalog", catalog)
                }.onFailure { Timber.w(it, "ankiquest deck progress unavailable; uploading reviews only") }
            }
            profile =
                execute(
                    Request
                        .Builder()
                        .url("$url/api/reviews/$user")
                        .header("Authorization", "Bearer $token")
                        .post(body.toString().toRequestBody(json))
                        .build(),
                )
            prefs.edit { putLong(MARK_KEY, maxOf(known, mark)) }
            first = false
        } while (full)
        if (!onlyTest) {
            prefs.edit {
                putString(RECENT_KEY, present.joinToString(","))
                putBoolean(initialSyncKey, true)
            }
        }
        return profile
    }

    private fun recentUploads(
        prefs: SharedPreferences,
        windowStart: Long,
    ): Set<Long> =
        prefs
            .getString(RECENT_KEY, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it > windowStart }
            .toSet()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private suspend fun present(
        profile: JSONObject,
        showFeedback: Boolean,
    ) {
        val snapshot = snapshotOf(profile)
        val message = previous?.let { describe(it, snapshot) }
        previous = snapshot
        app?.let { AnkiquestWidget.requestUpdate(it) }
        if (showFeedback && message != null) {
            withContext(Dispatchers.Main) { showBanner(message.first, message.second) }
        }
    }

    private suspend fun clock(): JSONObject {
        val rollover =
            CollectionManager.withCol {
                runCatching {
                    db.queryString("select cast(val as text) from config where key = 'rollover'").trim().toInt()
                }.getOrDefault(4)
            }
        val now = TimeManager.time.intTimeMS()
        return JSONObject()
            .put("rollover_hour", rollover)
            .put("offset_west_min", -TimeZone.getDefault().getOffset(now) / 60_000)
            .also { AnkiDroidApp.sharedPrefs().edit { putInt(AnkiquestNotifier.ROLLOVER_KEY, rollover) } }
    }

    private suspend fun pendingReviews(afterId: Long): JSONArray =
        CollectionManager.withCol {
            val rows = JSONArray()
            db
                .query(
                    "select id, cid, lastIvl, time, type from revlog " +
                        "where id > ? and ease > 0 and type < 4 order by id limit $MAX_PENDING",
                    afterId,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.put(
                            JSONObject()
                                .put("id", cursor.getLong(0))
                                .put("cid", cursor.getLong(1))
                                .put("last_ivl", cursor.getLong(2))
                                .put("time_ms", cursor.getLong(3))
                                .put("kind", cursor.getInt(4)),
                        )
                    }
                }
            rows
        }

    private suspend fun deckSnapshots(clock: JSONObject): JSONArray =
        CollectionManager.withCol {
            val now = TimeManager.time.intTimeMS()
            val offset = clock.getInt("offset_west_min")
            val rollover = clock.getInt("rollover_hour")
            val rows =
                AnkiquestDecks.snapshots(
                    this,
                    now,
                    offset,
                    rollover,
                )
            check(AnkiquestDecks.day(now, offset, rollover) == AnkiquestDecks.day(TimeManager.time.intTimeMS(), offset, rollover)) {
                "Study day changed while reading deck progress"
            }
            rows
        }

    private fun get(url: String): JSONObject = execute(Request.Builder().url(url).build())

    private fun execute(request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            JSONObject(response.body.string())
        }

    private class HttpStatusException(
        val code: Int,
    ) : IOException("HTTP $code")

    /**
     * Runs a connection test, or with [uploadAll] resends every review, for the settings screen.
     *
     * @return a message describing the outcome, never throws
     */
    suspend fun runFromSettings(
        context: Context,
        uploadAll: Boolean,
    ): String = withContext(Dispatchers.IO) { settingsAction(context, uploadAll) }

    private suspend fun settingsAction(
        context: Context,
        uploadAll: Boolean,
    ): String {
        val (url, user) = endpoint() ?: return context.getString(R.string.ankiquest_check_unconfigured)
        val token =
            AnkiDroidApp
                .sharedPrefs()
                .getString(TOKEN_KEY, "")
                .orEmpty()
                .trim()
        return try {
            mutex.withLock {
                val profile =
                    if (token.isEmpty()) {
                        get("$url/api/profile/$user")
                    } else {
                        if (uploadAll) {
                            AnkiDroidApp.sharedPrefs().edit {
                                remove(MARK_KEY)
                                remove("$INITIAL_SYNC_KEY:$url/$user")
                            }
                        }
                        upload(url, user, token, resync = false, onlyTest = !uploadAll)
                    }
                present(profile, showFeedback = false)
                val lifetime = profile.getJSONObject("lifetime")
                val summary =
                    context.getString(
                        R.string.ankiquest_check_ok,
                        profile.getString("display"),
                        profile.getInt("level"),
                        profile.getLong("xp_total"),
                        lifetime.getLong("reviews"),
                    )
                if (token.isEmpty()) summary + "\n\n" + context.getString(R.string.ankiquest_check_no_token) else summary
            }
        } catch (e: HttpStatusException) {
            when (e.code) {
                401 -> context.getString(R.string.ankiquest_check_unauthorized)
                404 -> context.getString(R.string.ankiquest_check_unknown_player)
                else -> context.getString(R.string.ankiquest_check_http, e.code)
            }
        } catch (e: UnknownHostException) {
            context.getString(R.string.ankiquest_check_unreachable, url)
        } catch (e: IllegalArgumentException) {
            context.getString(R.string.ankiquest_check_bad_url, url)
        } catch (e: Exception) {
            Timber.w(e, "ankiquest settings check failed")
            context.getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun snapshotOf(profile: JSONObject): Snapshot {
        val quests = profile.getJSONArray("quests")
        val achievements = profile.getJSONArray("achievements")
        return Snapshot(
            xp = profile.getLong("xp_total"),
            level = profile.getInt("level"),
            intoLevel = profile.getLong("xp_into_level"),
            forNext = profile.getLong("xp_for_next"),
            streak = profile.getInt("streak"),
            combo = profile.getJSONObject("today").getInt("current_combo"),
            doneQuests =
                (0 until quests.length())
                    .map { quests.getJSONObject(it) }
                    .filter { it.getBoolean("done") }
                    .map { it.getString("title") }
                    .toSet(),
            achievements =
                (0 until achievements.length())
                    .map { achievements.getJSONObject(it) }
                    .filter { !it.isNull("unlocked") }
                    .map { it.getString("title") }
                    .toSet(),
        )
    }

    private fun describe(
        before: Snapshot,
        after: Snapshot,
    ): Pair<String, Boolean>? {
        val gained = after.xp - before.xp
        if (gained == 0L) return null
        if (gained < 0) {
            val undone =
                buildString {
                    append("−${-gained} XP")
                    append("  ·  combo ${after.combo}")
                    append("  ·  Lv ${after.level}  ${after.intoLevel}/${after.forNext}")
                }
            return undone to false
        }
        val headlines = mutableListOf<String>()
        if (after.level > before.level) headlines += "Level ${after.level}!"
        (after.achievements - before.achievements).forEach { headlines += "Achievement: $it" }
        (after.doneQuests - before.doneQuests).forEach { headlines += "Quest complete: $it" }
        if (after.streak > before.streak) headlines += "${after.streak} day streak"

        val status =
            buildString {
                append("+$gained XP")
                if (after.combo >= 5) append("  ·  combo ${after.combo}")
                append("  ·  Lv ${after.level}  ${after.intoLevel}/${after.forNext}")
            }
        return if (headlines.isEmpty()) {
            status to false
        } else {
            (headlines.joinToString("\n") + "\n" + status) to true
        }
    }

    private fun showBanner(
        text: String,
        important: Boolean,
    ) {
        val host = activity.get() ?: return
        if (host.isFinishing || host.isDestroyed) return
        val root = host.findViewById<FrameLayout>(android.R.id.content) ?: return
        val density = host.resources.displayMetrics.density
        val banner =
            root.findViewWithTag<TextView>(BANNER_TAG) ?: TextView(host).apply {
                tag = BANNER_TAG
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO
                val pad = (10 * density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                elevation = 8 * density
                root.addView(
                    this,
                    FrameLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                        ).apply { topMargin = (72 * density).toInt() },
                )
            }
        banner.background =
            GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(if (important) 0xF0B8860B.toInt() else 0xE0202830.toInt())
            }
        banner.text = text
        banner.animate().cancel()
        banner.alpha = 1f
        banner.bringToFront()
        banner
            .animate()
            .alpha(0f)
            .setStartDelay(if (important) BANNER_LONG_MS else BANNER_MS)
            .setDuration(400)
            .start()
    }

    fun dashboardUrl(): String? = endpoint()?.let { (url, user) -> "$url/#$user" }

    override fun onActivityResumed(activity: Activity) {
        this.activity = WeakReference(activity)
        AnkiquestUpdater.maybeCheck(activity)
        val now = TimeManager.time.intTimeMS()
        if (now - resumeUploadAt > RESUME_UPLOAD_INTERVAL_MS) {
            resumeUploadAt = now
            launchRefresh(showFeedback = false, resync = true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (this.activity.get() === activity) this.activity.clear()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
