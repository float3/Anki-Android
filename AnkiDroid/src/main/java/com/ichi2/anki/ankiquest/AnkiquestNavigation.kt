// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.ankiquest

import android.content.Intent
import com.ichi2.anki.AnkiDroidApp
import java.security.MessageDigest

/** Entry policy is independent of connectivity: the local study library always works. */
object AnkiquestNavigation {
    const val OPEN_TODAY_KEY = "ankiquestOpenToday"
    const val SESSION_SUMMARY_KEY = "ankiquestSessionSummary"

    fun enabled(): Boolean = Ankiquest.dashboardUrl() != null

    fun opensToday(intent: Intent): Boolean =
        enabled() &&
            AnkiDroidApp.sharedPrefs().getBoolean(OPEN_TODAY_KEY, false) &&
            !intent.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false) &&
            !intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK) &&
            intent.action == Intent.ACTION_MAIN &&
            intent.data == null

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
