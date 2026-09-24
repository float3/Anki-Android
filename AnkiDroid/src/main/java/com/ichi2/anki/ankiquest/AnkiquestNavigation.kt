// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import java.security.MessageDigest

/** Entry policy is independent of connectivity: the local study library always works. */
object AnkiquestNavigation {
    const val OPEN_TODAY_KEY = "ankiquestOpenToday"
    const val SESSION_SUMMARY_KEY = "ankiquestSessionSummary"
    const val FRIENDS_PATH = "/community#challenges"
    const val ACTIVITY_PATH = "/community#activity"

    fun enabled(): Boolean = Ankiquest.dashboardUrl() != null

    fun opensToday(intent: Intent): Boolean =
        enabled() &&
            AnkiDroidApp.sharedPrefs().getBoolean(OPEN_TODAY_KEY, false) &&
            !intent.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false) &&
            !intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK) &&
            intent.action == Intent.ACTION_MAIN &&
            intent.data == null

    /** The website page for one friend goal. */
    fun challengePath(id: Long): String = "/community#challenge-$id"

    /** Where a bottom navigation item leads; null for Decks, the deck list itself. */
    fun destination(
        context: Context,
        item: Int,
    ): Intent? =
        when (item) {
            R.id.ankiquest_nav_today ->
                AnkiquestHomeActivity
                    .intent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            R.id.ankiquest_nav_friends -> AnkiquestActivity.intent(context, FRIENDS_PATH)
            R.id.ankiquest_nav_progress -> AnkiquestActivity.intent(context, null)
            else -> null
        }

    /** A credential change invalidates private presentation state, including a same-user token change. */
    fun accountFingerprint(): String {
        val settings = AnkiDroidApp.sharedPrefs().all
        val player =
            (settings[Ankiquest.USER_KEY] as? String).orEmpty().trim().ifEmpty {
                (settings["username"] as? String).orEmpty().trim()
            }
        val identity =
            listOf(
                settings[Ankiquest.URL_KEY].toString(),
                settings[Ankiquest.USER_KEY].toString(),
                player,
                settings[Ankiquest.TOKEN_KEY].toString(),
            ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
