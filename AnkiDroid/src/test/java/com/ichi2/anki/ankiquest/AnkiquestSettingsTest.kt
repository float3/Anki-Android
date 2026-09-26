// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenu
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestSettingsTest : RobolectricTest() {
    @Before
    fun configureAccount() {
        mockkObject(Ankiquest)
        // These UI tests do not load Anki's native collection backend in the background.
        every { Ankiquest.onActivityResumed(any()) } returns Unit
        coEvery { Ankiquest.dashboardSession(any()) } returns null
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, "https://quest.example/anki")
            putString(Ankiquest.USER_KEY, "cerro")
            putString(Ankiquest.TOKEN_KEY, "saved-test-token")
        }
    }

    @After
    fun clearMocks() {
        unmockkObject(Ankiquest)
    }

    @Test
    fun `web credentials come from one account snapshot when settings change during capture`() {
        val preferences = AnkiDroidApp.sharedPrefs()
        val previousOverride = AnkiDroidApp.sharedPreferencesTestingOverride
        val changingPreferences = mockk<SharedPreferences>()
        every { changingPreferences.getString(any(), any()) } answers { preferences.getString(firstArg(), secondArg()) }
        every { changingPreferences.all } answers {
            val captured = preferences.all
            preferences.edit {
                putString(Ankiquest.URL_KEY, "https://different.example")
                putString(Ankiquest.USER_KEY, "hill")
                putString(Ankiquest.TOKEN_KEY, "different-server-secret")
            }
            captured
        }
        AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
        try {
            val session = assertNotNull(Ankiquest.webSession())
            assertEquals("https://quest.example/anki/#cerro", session.dashboard)
            assertEquals("cerro", session.user)
            val script = assertNotNull(session.script(session.dashboard))
            assertTrue(script.contains("saved-test-token"))
            assertFalse(script.contains("different-server-secret"))
        } finally {
            AnkiDroidApp.sharedPreferencesTestingOverride = previousOverride
        }
    }

    @Test
    fun `streak protection is available in native settings`() {
        val keys = mutableListOf<String>()
        targetContext.resources.getXml(R.xml.preferences_ankiquest).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                val key = xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "key", 0)
                if (key != 0) keys.add(targetContext.getString(key))
            }
        }
        assertTrue("ankiquestStreakProtection" in keys, "The settings page must include the leaderboard's streak protection setting")
    }

    @Test
    fun `leaderboard offers the full native settings page`() {
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java).create().also { shadowOf(Looper.getMainLooper()).idle() }
        saveControllerForCleanup(controller)
        val menu = RoboMenu()
        controller.get().onCreateOptionsMenu(menu)
        assertTrue((0 until menu.size()).any { menu.getItem(it).title == targetContext.getString(R.string.settings) })
    }

    @Test
    fun `dashboard receives the saved account without asking for its token again`() {
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java).create().also { shadowOf(Looper.getMainLooper()).idle() }
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://quest.example/anki/?embed=1#cerro")
        val script = assertNotNull(shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(script.contains("saved-test-token"))
        assertTrue(script.contains("ankiquest-auth"))
        assertFalse(shadowOf(webView).lastLoadedUrl.contains("saved-test-token"))
    }

    @Test
    @SuppressLint("AuthLeak") // Deliberately rejected example credentials, not a real secret.
    fun `credentials are limited to the configured origin and known AnkiQuest pages`() {
        val session = AnkiquestWebSession("https://quest.example:443/anki/#cerro", "cerro", "secret")
        for (route in listOf("", "hour", "day", "week", "month", "year", "all", "records", "community")) {
            assertTrue(session.allows("https://quest.example/anki/$route#cerro"), route)
            assertNotNull(session.script("https://quest.example/anki/$route#cerro"))
        }
        for (url in listOf(
            "http://quest.example/anki/",
            "https://quest.example:8443/anki/",
            "https://other.example/anki/",
            "https://quest.example/elsewhere/",
            "https://quest.example/anki/login",
            "https://quest.example/anki/week/extra",
            "https://quest.example/anki/?page=external",
            "https://user:password@quest.example/anki/",
            "javascript:alert(1)",
        )) {
            assertFalse(session.allows(url), url)
            assertNull(session.script(url), url)
        }
        assertFalse(AnkiquestWebSession("https://user:password@quest.example/#cerro", "cerro", "secret").allows("https://quest.example/"))
    }

    @Test
    fun `community calendar links keep the saved account only for a single supported period`() {
        val session = AnkiquestWebSession("https://quest.example/anki/#cerro", "cerro", "secret")
        for (period in listOf("day", "week", "month")) {
            val url = "https://quest.example/anki/community?period=$period#calendar"
            assertTrue(session.allows(url), url)
            assertNotNull(session.script(url))
        }
        for (url in listOf(
            "https://quest.example/anki/community?period=year#calendar",
            "https://quest.example/anki/community?period=#calendar",
            "https://quest.example/anki/community?#calendar",
            "https://quest.example/anki/community?period=day&period=week#calendar",
            "https://quest.example/anki/community?period=day&period=day#calendar",
            "https://quest.example/anki/community?period=day&next=external#calendar",
            "https://quest.example/anki/community?next=external&period=day#calendar",
            "https://quest.example/anki/community?period=day&#calendar",
            "https://quest.example/anki/community?period=day?next=external#calendar",
            "https://quest.example/anki/community?%70eriod=day#calendar",
            "https://quest.example/anki/community?period=%64ay#calendar",
            "https://quest.example/anki/community?page=day#calendar",
            "https://quest.example/anki/week?period=day#calendar",
            "https://quest.example/anki/?period=day#calendar",
            "https://other.example/anki/community?period=day#calendar",
        )) {
            assertFalse(session.allows(url), url)
            assertNull(session.script(url), url)
        }
    }

    @Test
    fun `every embedded native destination keeps the configured prefix and encoded fragment`() {
        val routes = listOf("/", "/community", "/records", "/hour", "/day", "/week", "/month", "/year", "/all")
        for (prefix in listOf("/", "/prefix/")) {
            val dashboard = "https://quest.example$prefix#member%20name"
            val session = AnkiquestWebSession(dashboard, "member name", "test-token")
            for (route in routes) {
                for (fragment in listOf("", "#member%20name", "#challenge-42")) {
                    val destination = AnkiquestActivity.destinationUrl(dashboard, "$route$fragment")
                    val expected = "https://quest.example$prefix${route.removePrefix("/")}?embed=1$fragment"
                    assertEquals(expected, destination)
                    assertTrue(session.allows(destination), destination)
                    assertNotNull(session.script(destination), destination)
                }
            }
        }
    }

    @Test
    fun `embedded community calendars accept either order of the two supported parameters`() {
        for (prefix in listOf("/", "/prefix/")) {
            val session = AnkiquestWebSession("https://quest.example$prefix#member", "member", "test-token")
            for (period in listOf("day", "week", "month")) {
                for (query in listOf("period=$period&embed=1", "embed=1&period=$period")) {
                    val url = "https://quest.example${prefix}community?$query#calendar"
                    assertTrue(session.allows(url), url)
                    assertNotNull(session.script(url), url)
                }
            }
        }
    }

    @Test
    fun `embedded documents reject duplicate unknown empty and encoded query parameters`() {
        val routes = listOf("", "community", "records", "hour", "day", "week", "month", "year", "all")
        val queries =
            listOf(
                "",
                "embed",
                "embed=",
                "embed=0",
                "embed=2",
                "embed=true",
                "embed=1&embed=1",
                "embed=1&embed=0",
                "embed=1&unknown=x",
                "unknown=x&embed=1",
                "embed=1&token=test-token",
                "embed=1&",
                "&embed=1",
                "embed=1?unknown=x",
                "%65mbed=1",
                "embed=%31",
                "period=year&embed=1",
                "period=&embed=1",
                "period=day&period=day&embed=1",
                "period=day&period=week&embed=1",
                "period=day&embed=1&embed=1",
                "embed=1&period=day&embed=0",
                "period=day&embed=1&unknown=x",
                "%70eriod=day&embed=1",
                "period=%64ay&embed=1",
            )
        for (prefix in listOf("/", "/prefix/")) {
            val session = AnkiquestWebSession("https://quest.example$prefix#member", "member", "test-token")
            for (route in routes) {
                for (query in queries) {
                    val url = "https://quest.example$prefix$route?$query#member%20name"
                    assertFalse(session.allows(url), url)
                    assertNull(session.script(url), url)
                }
                if (route != "community") {
                    for (query in listOf("period=day", "period=day&embed=1", "embed=1&period=day")) {
                        val url = "https://quest.example$prefix$route?$query#calendar"
                        assertFalse(session.allows(url), url)
                        assertNull(session.script(url), url)
                    }
                }
            }
        }
    }

    @Test
    fun `embed does not authorize other paths origins or URL credentials`() {
        val session = AnkiquestWebSession("https://quest.example/prefix/#member", "member", "test-token")
        val rejected =
            listOf(
                "https://quest.example/prefix/auth/session?embed=1",
                "https://quest.example/prefix/api/profile/member?embed=1",
                "https://quest.example/prefix/unknown?embed=1",
                "https://quest.example/prefix/community/extra?embed=1",
                "https://quest.example/prefix/community/?embed=1",
                "https://quest.example/community?embed=1",
                "https://quest.example/prefix-other/community?embed=1",
                "http://quest.example/prefix/community?embed=1",
                "https://quest.example:8443/prefix/community?embed=1",
                "https://other.example/prefix/community?embed=1",
                "https://user@quest.example/prefix/community?embed=1",
            )
        for (url in rejected) {
            assertFalse(session.allows(url), url)
            assertNull(session.script(url), url)
        }
        val defaultPort = "https://quest.example:443/prefix/community?embed=1#member%20name"
        assertTrue(session.allows(defaultPort), defaultPort)
        assertNotNull(session.script(defaultPort), defaultPort)
    }

    @Test
    fun `removing saved credentials clears the page session`() {
        val session = AnkiquestWebSession("https://quest.example/#cerro", "cerro", "")
        assertTrue(assertNotNull(session.script("https://quest.example/")).contains("window.ankiquestSession = null"))
    }

    @Test
    fun `credential handoff safely encodes quotes and unicode`() {
        val user = "cé\"rro"
        val token = "private\"token\nnext line"
        val session = AnkiquestWebSession("https://quest.example:443/#cerro", user, token)
        val script = assertNotNull(session.script("https://quest.example/"))
        val credentials = JSONObject(script.substringAfter("window.ankiquestSession = ").substringBefore(";\n"))
        assertEquals(user, credentials.getString("user"))
        assertEquals(token, credentials.getString("token"))
        assertFalse(script.contains(":443"))
    }

    @Test
    fun `community reminders open with the same saved account`() {
        val intent = Intent(targetContext, AnkiquestActivity::class.java).putExtra(AnkiquestActivity.COMMUNITY_REMINDERS, true)
        val controller =
            Robolectric
                .buildActivity(
                    AnkiquestActivity::class.java,
                    intent,
                ).create()
                .also { shadowOf(Looper.getMainLooper()).idle() }
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        assertEquals("https://quest.example/anki/community?embed=1#reminders", shadowOf(webView).lastLoadedUrl)
        shadowOf(webView).webViewClient.onPageFinished(webView, shadowOf(webView).lastLoadedUrl)
        assertTrue(assertNotNull(shadowOf(webView).lastEvaluatedJavascript).contains("saved-test-token"))
    }

    @Test
    fun `dashboard does not inject credentials after a redirect to another document`() {
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java).create().also { shadowOf(Looper.getMainLooper()).idle() }
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        webView.loadUrl("https://quest.example/anki/login")
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://quest.example/anki/?embed=1#cerro")
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
    }

    @Test
    fun `returning from native settings reloads the dashboard even for the same account`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .also { shadowOf(Looper.getMainLooper()).idle() }
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        val menu = RoboMenu()
        activity.onCreateOptionsMenu(menu)
        activity.onOptionsItemSelected(menu.findItem(R.id.ankiquest_settings))
        val webView = activity.findViewById<WebView>(R.id.web_view)
        controller.pause().resume()
        assertEquals(1, shadowOf(webView).reloadInvocations)
    }

    @Test
    fun `equivalent URL edits restart a pending bootstrap with the new fingerprint`() {
        val firstBootstrap = CompletableDeferred<String?>()
        var bootstrapCalls = 0
        coEvery { Ankiquest.dashboardSession("https://quest.example/anki/#cerro") } coAnswers {
            if (++bootstrapCalls == 1) firstBootstrap.await() else null
        }
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .also { shadowOf(Looper.getMainLooper()).idle() }
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.web_view)
        assertEquals(1, bootstrapCalls)
        assertFalse(firstBootstrap.isCompleted)
        val previousSession = Ankiquest.webSession()
        val previousFingerprint = AnkiquestNavigation.accountFingerprint()
        val menu = RoboMenu()
        activity.onCreateOptionsMenu(menu)
        activity.onOptionsItemSelected(menu.findItem(R.id.ankiquest_settings))
        controller.pause()
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.URL_KEY, "https://quest.example/anki/") }
        assertEquals(previousSession, Ankiquest.webSession())
        assertNotEquals(previousFingerprint, AnkiquestNavigation.accountFingerprint())
        controller.resume()
        assertEquals("about:blank", shadowOf(webView).lastLoadedUrl)
        shadowOf(webView).webViewClient.onPageFinished(webView, "about:blank")
        shadowOf(Looper.getMainLooper()).idle()
        val destination = "https://quest.example/anki/?embed=1#cerro"
        assertEquals(destination, shadowOf(webView).lastLoadedUrl)
        coVerify(exactly = 2) { Ankiquest.dashboardSession("https://quest.example/anki/#cerro") }
        firstBootstrap.complete(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(destination, shadowOf(webView).lastLoadedUrl)
    }

    @Test
    fun `changing accounts reloads and clears history after the new page arrives`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .also { shadowOf(Looper.getMainLooper()).idle() }
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        controller.pause()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.USER_KEY, "hill")
            putString(Ankiquest.TOKEN_KEY, "new-token")
        }
        controller.resume()
        assertEquals("about:blank", shadowOf(webView).lastLoadedUrl)
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://quest.example/anki/?embed=1#cerro")
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
        shadowOf(webView).webViewClient.onPageFinished(webView, "about:blank")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://quest.example/anki/?embed=1#hill", shadowOf(webView).lastLoadedUrl)
        assertFalse(shadowOf(webView).wasClearHistoryCalled())
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://quest.example/anki/?embed=1#cerro")
        assertFalse(shadowOf(webView).wasClearHistoryCalled())
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
        shadowOf(webView).webViewClient.onPageFinished(webView, "https://quest.example/anki/?embed=1#hill")
        assertTrue(shadowOf(webView).wasClearHistoryCalled())
        val script = assertNotNull(shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(script.contains("new-token"))
        assertFalse(script.contains("saved-test-token"))
        coVerify(exactly = 1) { Ankiquest.dashboardSession("https://quest.example/anki/#cerro") }
        coVerify(exactly = 1) { Ankiquest.dashboardSession("https://quest.example/anki/#hill") }
    }

    @Test
    fun `rotating only the saved token also discards the previous page session`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .also { shadowOf(Looper.getMainLooper()).idle() }
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        controller.pause()
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "replacement-token") }
        controller.resume()
        assertEquals("about:blank", shadowOf(webView).lastLoadedUrl)
        shadowOf(webView).webViewClient.onPageFinished(webView, "about:blank")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://quest.example/anki/?embed=1#cerro", shadowOf(webView).lastLoadedUrl)
        shadowOf(webView).webViewClient.onPageFinished(webView, shadowOf(webView).lastLoadedUrl)
        val script = assertNotNull(shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(script.contains("replacement-token"))
        assertFalse(script.contains("saved-test-token"))
    }

    @Test
    fun `picture chooser follows the new configured server after returning from settings`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .also { shadowOf(Looper.getMainLooper()).idle() }
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        controller.pause()
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.URL_KEY, "https://new.example/quest") }
        controller.resume()
        shadowOf(webView).webViewClient.onPageFinished(webView, "about:blank")
        shadowOf(Looper.getMainLooper()).idle()
        val chrome = assertNotNull(shadowOf(webView).webChromeClient)
        assertEquals("https://new.example/quest/?embed=1#cerro", shadowOf(webView).lastLoadedUrl)
        assertTrue(chrome.onShowFileChooser(webView, mockk(relaxed = true), mockk()))
        webView.loadUrl("https://quest.example/anki/?embed=1#cerro")
        assertFalse(chrome.onShowFileChooser(webView, mockk(relaxed = true), mockk()))
    }
}
