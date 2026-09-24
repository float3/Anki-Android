// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestMobileNavigationTest : RobolectricTest() {
    @Before
    fun configure() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, "https://anki.example.test")
            putString(Ankiquest.USER_KEY, "member name")
            putString(Ankiquest.TOKEN_KEY, "member-token")
        }
    }

    @Test
    fun `launcher opens decks by default without an extra Today screen`() {
        AnkiDroidApp.sharedPrefs().edit { remove(AnkiquestNavigation.OPEN_TODAY_KEY) }
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `initializing settings keeps Decks as the default opening screen`() {
        AnkiDroidApp.sharedPrefs().edit { remove(AnkiquestNavigation.OPEN_TODAY_KEY) }
        PreferenceManager.setDefaultValues(targetContext, R.xml.preferences_ankiquest, true)
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `only a normal launcher entry opens Today`() {
        AnkiDroidApp.sharedPrefs().edit { putBoolean(AnkiquestNavigation.OPEN_TODAY_KEY, true) }
        assertTrue(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_VIEW)))
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN).putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)))
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN).putExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, 1L)))
        AnkiDroidApp.sharedPrefs().edit { putBoolean(AnkiquestNavigation.OPEN_TODAY_KEY, false) }
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `changing credentials invalidates the presentation identity`() {
        val before = AnkiquestNavigation.accountFingerprint()
        assertFalse(before.contains("member-token"))
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "replacement-token") }
        assertNotEquals(before, AnkiquestNavigation.accountFingerprint())
    }

    @Test
    fun `presentation identity captures fallback member in the same settings snapshot`() {
        val preferences = AnkiDroidApp.sharedPrefs()
        preferences.edit {
            remove(Ankiquest.USER_KEY)
            putString("username", "original-member")
        }
        val before = AnkiquestNavigation.accountFingerprint()
        val changingPreferences = mockk<SharedPreferences>()
        every { changingPreferences.all } answers {
            val snapshot = preferences.all
            preferences.edit { putString("username", "different-member") }
            snapshot
        }
        val previousOverride = AnkiDroidApp.sharedPreferencesTestingOverride
        try {
            AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
            assertEquals(before, AnkiquestNavigation.accountFingerprint())
        } finally {
            AnkiDroidApp.sharedPreferencesTestingOverride = previousOverride
        }
        assertNotEquals(before, AnkiquestNavigation.accountFingerprint())
    }

    @Test
    fun `web routes stay within the configured server and preserve encoded members`() {
        val dashboard = "https://anki.example.test/prefix/#member%20name"
        assertEquals(
            "https://anki.example.test/prefix/community?embed=1#challenge-42",
            AnkiquestActivity.destinationUrl(dashboard, "/community#challenge-42"),
        )
        assertEquals(
            "https://anki.example.test/prefix/week?embed=1#member%20name",
            AnkiquestActivity.destinationUrl(dashboard, "/week#member%20name"),
        )
        for (unsafe in listOf(
            "https://other.test/",
            "//other.test/",
            "/community/../../auth/logout",
            "/auth/session",
            "/community?token=secret",
        )) {
            assertEquals("https://anki.example.test/prefix/?embed=1#member%20name", AnkiquestActivity.destinationUrl(dashboard, unsafe))
        }
    }

    @Test
    fun `notification opens the relevant native goal with its owning account`() {
        val entry = JSONObject().put("id", 71).put("challenge_id", 42).put("route", "https://untrusted.test/")
        val intent = AnkiquestNotifier.notificationIntent(targetContext, entry, "https://anki.example.test/member%20name")
        assertEquals(ComponentName(targetContext, AnkiquestHomeActivity::class.java), intent.component)
        assertEquals(42L, intent.getLongExtra(AnkiquestHomeActivity.EXTRA_CHALLENGE_ID, 0))
        assertEquals(71L, intent.getLongExtra(AnkiquestHomeActivity.EXTRA_NOTIFICATION_ID, 0))
        assertEquals("https://anki.example.test/member%20name", intent.getStringExtra(AnkiquestHomeActivity.EXTRA_ACCOUNT))
        assertEquals(null, intent.data)
    }
}
