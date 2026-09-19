// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestUploadTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private var unsharedDeck = 0L
    private val requests = CopyOnWriteArrayList<UploadRequest>()

    @Volatile
    private var enabledDeck = "1"

    @Volatile
    private var settingsStatus = 200

    private data class UploadRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: JSONObject?,
    )

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        TimeManager.reset()
        addBasicNote()
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        unsharedDeck = col.decks.id("Geography")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val path = exchange.requestURI.path
            requests.add(
                UploadRequest(
                    exchange.requestMethod,
                    path,
                    exchange.requestHeaders.getFirst("Authorization"),
                    body.takeIf { it.isNotEmpty() }?.let(::JSONObject),
                ),
            )
            val settings = path.startsWith("/api/decks/")
            val response = if (settings) deckSettings() else profile()
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (settings) settingsStatus else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        configureAccount("cerro", "first-token")
        AnkiDroidApp.sharedPrefs().edit {
            remove("ankiquestUploadedThrough")
            remove("ankiquestRecentUploads")
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `notification settings sends an explicit complete catalog`() =
        runBlocking {
            Ankiquest.deckNotificationSettings()

            val upload = reviewUpload()
            assertEquals(setOf("1", unsharedDeck.toString()), deckIds(upload))
            assertTrue(upload.optBoolean("catalog"), "Only an explicit complete catalog may prune deleted decks")
        }

    @Test
    fun `opening notification settings never announces a completed shared deck`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit {
                putBoolean("ankiquestInitialSyncDone:$url/cerro", true)
            }

            Ankiquest.deckNotificationSettings()

            val upload = reviewUpload()
            assertTrue(upload.getBoolean("silent"), "Opening settings must not notify selected recipients")
            val decks = upload.getJSONArray("decks")
            val completed = (0 until decks.length()).map { decks.getJSONObject(it) }.single { it.getString("id") == "1" }
            assertEquals(0L, completed.getLong("remaining"))
            assertEquals(1L, completed.getLong("reviewed_today"))
        }

    @Test
    fun `review uploads include only authenticated enabled decks`() =
        runBlocking {
            uploadReviews()

            val upload = reviewUpload()
            assertEquals(setOf("1"), deckIds(upload))
            assertFalse(upload.optBoolean("catalog"), "A partial progress update must not prune other decks")
            assertEquals(1, upload.getJSONArray("reviews").length())
            assertEquals("Bearer first-token", requests.single { it.path == "/api/decks/cerro" }.authorization)
        }

    @Test
    fun `no enabled decks sends reviews without deck progress`() =
        runBlocking {
            enabledDeck = ""

            uploadReviews()

            assertFalse(reviewUpload().has("decks"))
            assertEquals(1, reviewUpload().getJSONArray("reviews").length())
        }

    @Test
    fun `failed settings lookup preserves reviews without sharing unselected progress`() =
        runBlocking {
            settingsStatus = 503

            uploadReviews()

            assertFalse(reviewUpload().has("decks"))
            assertEquals(1, reviewUpload().getJSONArray("reviews").length())
            assertEquals(1, requests.count { it.path == "/api/decks/cerro" })
        }

    @Test
    fun `account changes use the new authenticated sharing selection`() =
        runBlocking {
            uploadReviews()
            requests.clear()
            configureAccount("other", "second-token")
            enabledDeck = unsharedDeck.toString()

            uploadReviews()

            assertEquals(setOf(unsharedDeck.toString()), deckIds(reviewUpload("other")))
            assertEquals("Bearer second-token", requests.single { it.path == "/api/decks/other" }.authorization)
            assertEquals("Bearer second-token", requests.single { it.path == "/api/reviews/other" }.authorization)
            assertTrue(requests.none { it.path.endsWith("/cerro") })
        }

    @Test
    fun `disabling sharing is respected on the next review upload`() =
        runBlocking {
            uploadReviews()
            requests.clear()
            enabledDeck = ""

            uploadReviews()

            assertFalse(reviewUpload().has("decks"))
        }

    @Test
    fun `connection tests do not fetch settings or upload deck progress`() =
        runBlocking {
            Ankiquest.runFromSettings(targetContext, uploadAll = false)

            assertEquals(1, requests.size)
            assertFalse(reviewUpload().has("decks"))
            assertEquals(0, reviewUpload().getJSONArray("reviews").length())
            assertTrue(reviewUpload().getBoolean("silent"))
        }

    private fun configureAccount(
        user: String,
        token: String,
    ) {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, url)
            putString(Ankiquest.USER_KEY, user)
            putString(Ankiquest.TOKEN_KEY, token)
        }
    }

    private suspend fun uploadReviews() {
        assertEquals(
            targetContext.getString(R.string.ankiquest_check_ok, "Cerro", 1, 0L, 1L),
            Ankiquest.runFromSettings(targetContext, uploadAll = true),
        )
    }

    private fun reviewUpload(user: String = "cerro"): JSONObject =
        requests.single { it.method == "POST" && it.path == "/api/reviews/$user" }.body!!

    private fun deckIds(upload: JSONObject): Set<String> {
        val decks = upload.getJSONArray("decks")
        return (0 until decks.length()).map { decks.getJSONObject(it).getString("id") }.toSet()
    }

    private fun deckSettings(): JSONObject =
        JSONObject()
            .put(
                "decks",
                JSONArray(
                    listOf("1", unsharedDeck.toString()).map {
                        JSONObject().put("id", it).put("enabled", it == enabledDeck)
                    },
                ),
            ).put("recipients", JSONArray())

    private fun profile(): JSONObject =
        JSONObject()
            .put("display", "Cerro")
            .put("xp_total", 0)
            .put("level", 1)
            .put("xp_into_level", 0)
            .put("xp_for_next", 100)
            .put("streak", 0)
            .put("today", JSONObject().put("current_combo", 0))
            .put("lifetime", JSONObject().put("reviews", 1))
            .put("quests", JSONArray())
            .put("achievements", JSONArray())
}
