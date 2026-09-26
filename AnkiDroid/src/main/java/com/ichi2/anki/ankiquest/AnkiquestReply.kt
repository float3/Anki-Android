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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.R
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Answering a deck completion from its notification: a canned cheer, or your own words. */
object AnkiquestReply {
    enum class Outcome { SENT, RETRY, FAILED }

    const val NOTIFICATION_KEY = "notification"
    const val TAG_KEY = "tag"
    const val TITLE_KEY = "title"
    const val BODY_KEY = "body"
    const val MESSAGE_KEY = "message"
    const val ACCOUNT_KEY = "ankiquest.reply_account"
    const val SCOPE_KEY = "ankiquest.reply_scope"
    const val QUICK_ACTION = "com.ichi2.anki.ankiquest.REPLY_QUICK"
    const val CUSTOM_ACTION = "com.ichi2.anki.ankiquest.REPLY_CUSTOM"

    const val ATTEMPTS = 3
    const val MAX_MESSAGE = 200

    private const val WORK_NAME = "ankiquestReply"

    /** The cheer and the free text share one receiver, so both arrive as a plain message. */
    fun actions(
        context: Context,
        notification: Long,
        tag: Int,
        title: String,
        body: String,
        account: String?,
        scope: String?,
    ): List<NotificationCompat.Action> {
        val current = AnkiquestHomeData.account() ?: return emptyList()
        if (scope.isNullOrEmpty() || current.scope != scope || current.notificationAccount != account) return emptyList()
        val cheer = AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_cheer)
        val intent = { action: String, message: String? ->
            Intent(context, AnkiquestReplyReceiver::class.java)
                .setAction(action)
                .putExtra(NOTIFICATION_KEY, notification)
                .putExtra(TAG_KEY, tag)
                .putExtra(TITLE_KEY, title)
                .putExtra(BODY_KEY, body)
                .putExtra(MESSAGE_KEY, message)
                .putExtra(ACCOUNT_KEY, account)
                .putExtra(SCOPE_KEY, scope)
        }
        val pending = { action: String, message: String?, mutable: Boolean ->
            PendingIntent.getBroadcast(
                context,
                tag,
                intent(action, message),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val reply =
            RemoteInput
                .Builder(MESSAGE_KEY)
                .setLabel(AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_hint))
                .setChoices(AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_choices))
                .build()
        return listOf(
            NotificationCompat.Action
                .Builder(R.drawable.ic_star_notify, cheer, pending(QUICK_ACTION, cheer, false))
                .build(),
            NotificationCompat.Action
                .Builder(
                    R.drawable.ic_star_notify,
                    AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply),
                    pending(CUSTOM_ACTION, null, true),
                ).addRemoteInput(reply)
                .setAllowGeneratedReplies(false)
                .build(),
        )
    }

    fun send(
        context: Context,
        data: Data,
    ) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME:${data.getString(SCOPE_KEY)}:${data.getInt(TAG_KEY, 0)}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AnkiquestReplyWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(data)
                    .build(),
            )
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not queue a reply")
        }
    }

    /** The message as the receiver sees it: typed text wins, else the canned cheer. */
    fun message(intent: Intent): String =
        (
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MESSAGE_KEY)?.toString()
                ?: intent.getStringExtra(MESSAGE_KEY)
        ).orEmpty()
            .trim()
            .take(MAX_MESSAGE)

    fun data(
        intent: Intent,
        message: String,
    ): Data =
        Data
            .Builder()
            .putLong(NOTIFICATION_KEY, intent.getLongExtra(NOTIFICATION_KEY, 0))
            .putInt(TAG_KEY, intent.getIntExtra(TAG_KEY, 0))
            .putString(TITLE_KEY, intent.getStringExtra(TITLE_KEY))
            .putString(BODY_KEY, intent.getStringExtra(BODY_KEY))
            .putString(MESSAGE_KEY, message)
            .putString(ACCOUNT_KEY, intent.getStringExtra(ACCOUNT_KEY))
            .putString(SCOPE_KEY, intent.getStringExtra(SCOPE_KEY))
            .build()

    suspend fun run(
        context: Context,
        data: Data,
        attempt: Int,
    ): Outcome {
        val message = data.getString(MESSAGE_KEY).orEmpty()
        val notification = data.getLong(NOTIFICATION_KEY, 0)
        val account = data.getString(ACCOUNT_KEY).orEmpty()
        val scope = data.getString(SCOPE_KEY).orEmpty()
        if (message.isEmpty() || notification <= 0 || account.isEmpty() || scope.isEmpty() || !current(data)) return Outcome.FAILED
        return try {
            AnkiquestNotifier.onReplySent(context, data, Ankiquest.reply(notification, message, account, scope))
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never republish the old account's private notification after a switch.
            if (!current(data)) return Outcome.FAILED
            Timber.w(e, "ankiquest reply failed")
            // A refused reply stays refused; only a broken connection is worth another try.
            val done = e is Ankiquest.Rejected || attempt + 1 >= ATTEMPTS
            if (done) AnkiquestNotifier.onReplyFailed(context, data)
            if (done) Outcome.FAILED else Outcome.RETRY
        }
    }

    internal fun current(data: Data): Boolean {
        val account = AnkiquestHomeData.account() ?: return false
        return account.token.isNotEmpty() && account.notificationAccount == data.getString(ACCOUNT_KEY) &&
            account.scope == data.getString(SCOPE_KEY)
    }
}

class AnkiquestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val message = AnkiquestReply.message(intent)
        if (message.isEmpty()) return
        AnkiquestReply.send(context, AnkiquestReply.data(intent, message))
    }
}

class AnkiquestReplyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (AnkiquestReply.run(applicationContext, inputData, runAttemptCount)) {
            AnkiquestReply.Outcome.SENT -> Result.success()
            AnkiquestReply.Outcome.RETRY -> Result.retry()
            AnkiquestReply.Outcome.FAILED -> Result.failure()
        }
}
