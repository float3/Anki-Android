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
import androidx.annotation.VisibleForTesting
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
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Shows XP, combo, quest and level feedback from an ankiquest server after each answer.
 *
 * With a token, new review log rows are uploaded to the server. Without one, they are only
 * sent to its preview endpoint, which stores nothing, when the server permits public access.
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
    private val sessionClient =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    private var app: Application? = null
    private var activity = WeakReference<Activity>(null)
    private var syncedLastId = 0L
    private var baselineAt = 0L

    @VisibleForTesting
    internal var resumeUploadAt = 0L
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
            val (url, user, token) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            get("$url/api/profile/$user", token)
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

    private fun endpoint(): Triple<String, String, String>? {
        // Keep credentials tied to one server even when settings change during a queued request.
        val settings = AnkiDroidApp.sharedPrefs().all
        val url = (settings[URL_KEY] as? String).orEmpty().trim().trimEnd('/')
        val user = (settings[USER_KEY] as? String).orEmpty().trim().ifEmpty { Prefs.username.orEmpty() }
        val token = (settings[TOKEN_KEY] as? String).orEmpty().trim()
        if (url.isEmpty() || user.isEmpty()) return null
        return Triple(url, URLEncoder.encode(user, "UTF-8").replace("+", "%20"), token)
    }

    /** This week's standings, as served by `/api/leaderboard`. */
    suspend fun leaderboard(): JSONArray =
        withContext(Dispatchers.IO) {
            val (url, _, token) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            client.newCall(readRequest("$url/api/leaderboard", token)).execute().use { response ->
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

    /** Shares every deck in [shared] with [recipients] and stops sharing [unshared], in one request. */
    suspend fun saveDeckNotificationSettings(
        shared: List<String>,
        unshared: List<String>,
        recipients: List<String>,
    ) = withContext(Dispatchers.IO) {
        val (url, user, token) = authenticatedEndpoint()
        val decks = JSONArray()
        for (id in shared) {
            decks.put(JSONObject().put("id", id).put("enabled", true).put("recipients", JSONArray(recipients)))
        }
        for (id in unshared) {
            decks.put(JSONObject().put("id", id).put("enabled", false).put("recipients", JSONArray()))
        }
        execute(
            Request
                .Builder()
                .url("$url/api/decks/$user")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("decks", decks).toString().toRequestBody(json))
                .build(),
        )
    }

    /** Whether the server sends this player nudges, without touching deck progress. */
    suspend fun nudgesEnabled(): Boolean {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            fetchDeckNotificationSettings(url, user, token).getBoolean("nudges")
        }
    }

    suspend fun setNudges(enabled: Boolean): JSONObject {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url("$url/api/decks/$user")
                    .header("Authorization", "Bearer $token")
                    .post(
                        JSONObject()
                            .put("decks", JSONArray())
                            .put("nudges", enabled)
                            .toString()
                            .toRequestBody(json),
                    ).build(),
            )
        }
    }

    /** Streak protection is stored on the server; never infer it from a local default. */
    suspend fun streakProtectionEnabled(): Boolean {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url("$url/api/streak-freezes/$user")
                    .header("Authorization", "Bearer $token")
                    .build(),
            ).getBoolean("enabled")
        }
    }

    suspend fun setStreakProtection(enabled: Boolean): Boolean {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url("$url/api/streak-freezes/$user")
                    .header("Authorization", "Bearer $token")
                    .post(JSONObject().put("enabled", enabled).toString().toRequestBody(json))
                    .build(),
            ).getBoolean("enabled")
        }
    }

    /** The ids of every deck nested below the deck called [name] in the settings deck list. */
    fun subdeckIds(
        decks: List<JSONObject>,
        name: String,
    ): List<String> = decks.filter { it.getString("name").startsWith("$name::") }.map { it.getString("id") }

    /** A reply the server will never take, such as one already answered elsewhere. */
    class Rejected(
        val code: Int,
    ) : IOException("HTTP $code")

    /** Answers one inbox notification; returns the name of whoever will read it. */
    suspend fun reply(
        notification: Long,
        message: String,
        expectedAccount: String,
        expectedScope: String,
    ): String =
        withContext(Dispatchers.IO) {
            val account = AnkiquestHomeData.account() ?: throw Rejected(401)
            if (account.token.isEmpty() || account.notificationAccount != expectedAccount ||
                account.scope != expectedScope
            ) {
                throw Rejected(401)
            }
            val request =
                Request
                    .Builder()
                    .url(account.url("api/reply/${account.encodedUser}"))
                    .header("Authorization", "Bearer ${account.token}")
                    .post(
                        JSONObject()
                            .put("notification", notification)
                            .put("message", message)
                            .toString()
                            .toRequestBody(json),
                    ).build()
            try {
                val recipient = execute(request, sessionClient).getString("sent_to")
                if (AnkiquestHomeData.account()?.scope != account.scope) throw Rejected(401)
                recipient
            } catch (e: HttpStatusException) {
                throw if (e.code in 400..499) Rejected(e.code) else e
            }
        }

    internal data class CompletionNotifications(
        val account: String,
        val notifications: JSONArray,
        val scope: String,
    )

    /** Preserve the exact fetch identity for both delivery cursors and queued reply actions. */
    internal suspend fun completionNotifications(): CompletionNotifications? =
        withContext(Dispatchers.IO) {
            val account = AnkiquestHomeData.account() ?: return@withContext null
            if (account.token.isEmpty()) return@withContext null
            client
                .newCall(
                    Request
                        .Builder()
                        .url(account.url("api/notifications/${account.encodedUser}"))
                        .header("Authorization", "Bearer ${account.token}")
                        .build(),
                ).execute()
                .use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    CompletionNotifications(account.notificationAccount, JSONArray(response.body.string()), account.scope)
                }
        }

    private fun authenticatedEndpoint(): Triple<String, String, String> {
        val (url, user, token) = endpoint() ?: throw IllegalStateException("Set the server URL and player first.")

        check(token.isNotEmpty()) { "Set your ankiquest token to manage settings." }
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
        val (url, user, token) = endpoint() ?: return

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
        val progress = previous?.let { describe(it, snapshot) }
        previous = snapshot
        app?.let { AnkiquestWidget.requestUpdate(it) }
        val announced = announcements(profile)
        val message =
            when {
                announced == null -> progress?.takeIf { showFeedback }
                progress == null -> announced to true
                else -> "$announced\n${progress.first}" to true
            }
        if (message != null) {
            withContext(Dispatchers.Main) { showBanner(message.first, message.second) }
        }
    }

    /** What this upload just told other people about, so finishing a deck is visibly shared. */
    private fun announcements(profile: JSONObject): String? {
        val announced = profile.optJSONArray("announced")?.objects().orEmpty()
        if (announced.isEmpty()) return null
        return announced.joinToString("\n") {
            val people = it.getInt("recipients")
            "\uD83D\uDCE3 ${it.getString("deck")} \u2014 told $people ${if (people == 1) "friend" else "friends"}"
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

    private fun readRequest(
        url: String,
        token: String,
    ): Request =
        Request
            .Builder()
            .url(url)
            .apply {
                token.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
            }.build()

    private fun get(
        url: String,
        token: String = "",
    ): JSONObject = execute(readRequest(url, token))

    /** Exchange the configured token for an HttpOnly browser cookie without exposing it to the page. */
    internal suspend fun dashboardSession(dashboard: String): String? =
        withContext(Dispatchers.IO) {
            val (url, user, token) = endpoint() ?: return@withContext null
            if (dashboard != "$url/#$user") return@withContext null
            if (token.isEmpty()) return@withContext null
            val request =
                Request
                    .Builder()
                    .url("$url/auth/session")
                    .header("Authorization", "Bearer $token")
                    .post(ByteArray(0).toRequestBody())
                    .build()
            sessionClient.newCall(request).execute().use { response ->
                // Older public servers do not have the session endpoint.
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                response.headers.values("Set-Cookie").firstOrNull { value ->
                    val cookie = Cookie.parse(request.url, value)
                    cookie != null && cookie.name == "ankiquest_session" && cookie.hostOnly &&
                        cookie.path == "/" && cookie.httpOnly && (!request.url.isHttps || cookie.secure)
                } ?: throw IOException("The ankiquest server did not return a valid session cookie")
            }
        }

    private fun execute(
        request: Request,
        requestClient: OkHttpClient = client,
    ): JSONObject =
        requestClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            JSONObject(response.body.string())
        }

    internal class HttpStatusException(
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
        val (url, user, token) = endpoint() ?: return context.getString(R.string.ankiquest_check_unconfigured)

        return try {
            mutex.withLock {
                val profile =
                    if (token.isEmpty()) {
                        get("$url/api/profile/$user", token)
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
                // Discoverable when navigating with accessibility; the persistent session recap
                // carries this feedback without interrupting every answer with an announcement.
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
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

    internal fun webSession(): AnkiquestWebSession? =
        endpoint()?.let { (url, user, token) ->
            AnkiquestWebSession(
                "$url/#$user",
                URLDecoder.decode(user, "UTF-8"),
                token,
            )
        }

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
