// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestHomeActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "member", "secret")
    private val profile =
        JSONObject()
            .put("level", 2)
            .put("streak", 3)
            .put(
                "quests",
                JSONArray().put(JSONObject().put("title", "A little practice").put("progress", 6).put("target", 10)),
            )

    @Before
    fun prepareHome() {
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        coEvery { AnkiquestHomeData.profile(identity) } returns profile
    }

    @After
    fun clearHomeMocks() {
        unmockkObject(AnkiquestHomeData)
    }

    @Test
    fun `Today studies the local focus deck and shows server quests`() {
        val home = launch()
        assertTrue(home.hasText("Spanish"))
        assertTrue(home.hasText(home.getString(R.string.aq_home_streak_level, 3, 2)))
        assertTrue(home.hasText("A little practice"))
        home.click(home.getString(R.string.aq_home_study_due, 24))
        val study = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(DeckPicker::class.java.name, study.component?.className)
        assertEquals(12L, study.getLongExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, 0))
        assertTrue(study.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, study.flags)
    }

    @Test
    fun `offline Today keeps local study`() {
        coEvery { AnkiquestHomeData.profile(identity) } throws IOException("offline")
        val home = launch()
        assertTrue(home.hasText("Spanish"))
        assertTrue(home.hasText(home.getString(R.string.aq_home_offline)))
        assertFalse(home.hasText("A little practice"))
    }

    @Test
    fun `rejected credentials point to settings`() {
        coEvery { AnkiquestHomeData.profile(identity) } throws Ankiquest.HttpStatusException(401)
        val home = launch()
        assertTrue(home.hasText(home.getString(R.string.aq_home_auth)))
        assertTrue(home.hasText(home.getString(R.string.aq_home_settings)))
    }

    @Test
    fun `navigation keeps Today native and opens community pages on the website`() {
        val home = launch()
        val nav = home.findViewById<BottomNavigationView>(R.id.aq_home_navigation)
        assertEquals(4, nav.menu.size)

        nav.selectedItemId = R.id.ankiquest_nav_friends
        assertWebsite(home, AnkiquestNavigation.FRIENDS_PATH)
        assertEquals(R.id.ankiquest_nav_today, nav.selectedItemId)

        nav.selectedItemId = R.id.ankiquest_nav_progress
        assertWebsite(home, null)

        home.findViewById<View>(R.id.aq_home_activity).performClick()
        assertWebsite(home, AnkiquestNavigation.ACTIVITY_PATH)

        nav.selectedItemId = R.id.ankiquest_nav_decks
        val decks = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(DeckPicker::class.java.name, decks.component?.className)
        assertFalse(decks.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK))
        assertTrue(decks.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertEquals(R.id.ankiquest_nav_today, nav.selectedItemId)
    }

    private fun assertWebsite(
        home: AnkiquestHomeActivity,
        path: String?,
    ) {
        val opened = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(AnkiquestActivity::class.java.name, opened.component?.className)
        assertEquals(path, opened.getStringExtra(AnkiquestActivity.EXTRA_PATH))
        assertNull(opened.getStringExtra(AnkiquestActivity.EXTRA_ACCOUNT))
    }

    private fun launch(): AnkiquestHomeActivity =
        startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, AnkiquestHomeActivity.intent(targetContext))
            .also { advanceRobolectricLooper() }

    private fun AnkiquestHomeActivity.hasText(value: String): Boolean =
        findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().any { it.text.toString() == value }

    private fun AnkiquestHomeActivity.click(label: String) {
        val control =
            findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().first {
                it.text.toString() == label && it.isClickable
            }
        assertTrue(control.isEnabled)
        control.performClick()
        advanceRobolectricLooper()
    }
}
