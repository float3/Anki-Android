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

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Data
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject

/** Leaderboard position and streak notifications. */
object AnkiquestNotifier {
    const val RANK_KEY = "ankiquestNotifyRank"
    const val STREAK_HOURS_KEY = "ankiquestStreakReminderHours"
    const val DEFAULT_STREAK_HOURS = "2"

    private const val CHANNEL = "ankiquest"

    /** Keep nudges independently configurable, including channels saved by older versions. */
    private const val NUDGE_CHANNEL = "ankiquestNudges"
    private val BUZZ = longArrayOf(0, 250, 150, 250)
    private const val ORDER_KEY = "ankiquestLastOrder"
    private const val STREAK_DAY_KEY = "ankiquestStreakNotifiedDay"
    private const val RANK_ID = 5_130_001
    private const val STREAK_ID = 5_130_002
    private const val HOUR_MS = 60 * 60 * 1000L

    @Synchronized
    fun onDeckCompletions(
        context: Context,
        account: String,
        notifications: JSONArray,
        scope: String? = null,
    ) {
        if (scope != null && AnkiquestHomeData.account()?.scope != scope) return
        val prefs = AnkiDroidApp.sharedPrefs()
        val key = "ankiquestCompletionCursor:$account"
        val firstPollKey = "ankiquestCompletionFirstPollAt:$account"
        val entries = (0 until notifications.length()).map { notifications.getJSONObject(it) }.sortedBy { it.getLong("id") }
        var previous = prefs.getLong(key, 0L)
        // Only discard an old backlog on first contact. Unseen messages must not
        // age out while offline or waiting for notification permission.
        val firstPoll =
            if (prefs.contains(firstPollKey)) {
                prefs.getLong(firstPollKey, 0L)
            } else {
                val start = if (prefs.contains(key)) 0L else TimeManager.time.intTimeMS() / 1000
                prefs.edit { putLong(firstPollKey, start) }
                start
            }
        for (entry in entries) {
            val id = entry.getLong("id")
            if (id <= previous) continue
            val fresh = AnkiquestCompletionPolicy.freshNotification(entry.optLong("created_at"), firstPoll)
            val tag = 5_140_000 + (id % 1_000_000).toInt()
            val title = entry.getString("title")
            val body = entry.getString("body")
            val answerable = entry.optString("sender").isNotEmpty() && !entry.optBoolean("replied")
            if (fresh &&
                notify(
                    context,
                    tag,
                    title,
                    body,
                    notificationIntent(context, entry, account),
                    if (answerable) {
                        AnkiquestReply.actions(
                            context,
                            id,
                            tag,
                            title,
                            body,
                            account,
                            scope,
                            entry.optString("kind", "completion"),
                        )
                    } else {
                        emptyList()
                    },
                    entry.optString("kind") == "nudge",
                ) == Delivery.DISABLED
            ) {
                return
            }
            previous = id
            prefs.edit { putLong(key, previous) }
        }
    }

    /** Asks the server how this player moved since the order stored here, and says so when they did. */
    suspend fun onLeaderboard(context: Context) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val previous =
            prefs
                .getString(ORDER_KEY, null)
                ?.split(',')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val result = Ankiquest.rank(previous)
        val order = result.getJSONArray("order").let { users -> (0 until users.length()).map { users.getString(it) } }
        prefs.edit { putString(ORDER_KEY, order.joinToString(",")) }
        if (!prefs.getBoolean(RANK_KEY, true)) return
        val change = result.optJSONObject("change") ?: return
        notify(
            context,
            RANK_ID,
            change.getString("title"),
            change.getString("body"),
            AnkiquestActivity.intent(context, "/week").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Posts the server's streak warning once per study day, within the chosen hours before it ends. */
    fun onProfile(
        context: Context,
        profile: JSONObject,
    ) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val hours = prefs.getString(STREAK_HOURS_KEY, DEFAULT_STREAK_HOURS)?.toIntOrNull() ?: 0
        val warning = profile.optJSONObject("streak_warning")
        if (hours <= 0 || warning == null) return
        val dayEndsAt = profile.optLong("day_ends_at")
        val remaining = dayEndsAt - TimeManager.time.intTimeMS()
        if (remaining <= 0 || remaining > hours * HOUR_MS) return
        if (prefs.getLong(STREAK_DAY_KEY, Long.MIN_VALUE) == dayEndsAt) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        if (notify(context, STREAK_ID, warning.getString("title"), warning.getString("body"), open) != Delivery.DISABLED) {
            prefs.edit { putLong(STREAK_DAY_KEY, dayEndsAt) }
        }
    }

    /** Replaces the answered notification with what was said, so the reply is visibly gone. */
    fun onReplySent(
        context: Context,
        data: Data,
        who: String,
    ) {
        if (!AnkiquestReply.current(data)) return
        val message = data.getString(AnkiquestReply.MESSAGE_KEY).orEmpty()
        notify(
            context,
            data.getInt(AnkiquestReply.TAG_KEY, 0),
            data.getString(AnkiquestReply.TITLE_KEY).orEmpty(),
            context.getString(R.string.ankiquest_reply_sent, who, message),
            replyIntent(context, data),
            silent = true,
        )
    }

    /** Keeps the buttons so a reply that never left can be sent again. */
    fun onReplyFailed(
        context: Context,
        data: Data,
    ) {
        if (!AnkiquestReply.current(data)) return
        val tag = data.getInt(AnkiquestReply.TAG_KEY, 0)
        val title = data.getString(AnkiquestReply.TITLE_KEY).orEmpty()
        val body = data.getString(AnkiquestReply.BODY_KEY).orEmpty()
        val message = data.getString(AnkiquestReply.MESSAGE_KEY).orEmpty()
        notify(
            context,
            tag,
            title,
            context.getString(R.string.ankiquest_reply_failed, message),
            replyIntent(context, data),
            AnkiquestReply.actions(
                context,
                data.getLong(AnkiquestReply.NOTIFICATION_KEY, 0),
                tag,
                title,
                body,
                data.getString(AnkiquestReply.ACCOUNT_KEY),
                data.getString(AnkiquestReply.SCOPE_KEY),
                data.getString(AnkiquestReply.KIND_KEY) ?: "completion",
            ),
            silent = true,
        )
    }

    private fun replyIntent(
        context: Context,
        data: Data,
    ): Intent =
        AnkiquestActivity
            .intent(context, AnkiquestNavigation.ACTIVITY_PATH, data.getString(AnkiquestReply.ACCOUNT_KEY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** IDs become known website routes; server-supplied URLs never become arbitrary app destinations. */
    internal fun notificationIntent(
        context: Context,
        entry: JSONObject,
        account: String,
    ): Intent {
        val challenge = entry.optLong("challenge_id")
        val path = if (challenge > 0) AnkiquestNavigation.challengePath(challenge) else AnkiquestNavigation.ACTIVITY_PATH
        return AnkiquestActivity.intent(context, path, account).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Existing channel behavior belongs to Android settings, not app updates. */
    fun alertSettingsIntent(
        context: Context,
        nudge: Boolean,
    ): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = ensureChannel(context, nudge)
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }

    private fun ensureChannel(
        context: Context,
        nudge: Boolean,
    ): String {
        val channel = if (nudge) NUDGE_CHANNEL else CHANNEL
        val manager = NotificationManagerCompat.from(context)
        if (manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(channel, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(if (nudge) R.string.ankiquest_nudges_title else R.string.ankiquest_screen_title))
                    .setVibrationEnabled(true)
                    .setVibrationPattern(BUZZ)
                    .build(),
            )
        }
        return channel
    }

    private enum class Delivery {
        POSTED,
        CHANNEL_BLOCKED,
        DISABLED,
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        id: Int,
        title: String,
        body: String,
        open: Intent,
        actions: List<NotificationCompat.Action> = emptyList(),
        nudge: Boolean = false,
        silent: Boolean = false,
    ): Delivery {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Delivery.DISABLED
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Delivery.DISABLED
        val channel = ensureChannel(context, nudge)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return Delivery.CHANNEL_BLOCKED
        }
        val notification =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_star_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                // Before channels, Android reads alert behavior from the notification.
                // The system still applies the phone's ringer and Do Not Disturb settings.
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setVibrate(BUZZ)
                .setSilent(silent)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        id,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).apply { actions.forEach { addAction(it) } }
                .build()
        return try {
            manager.notify(id, notification)
            Delivery.POSTED
        } catch (_: SecurityException) {
            // Permission may be revoked after the check above. Keep the inbox pending.
            Delivery.DISABLED
        }
    }
}
