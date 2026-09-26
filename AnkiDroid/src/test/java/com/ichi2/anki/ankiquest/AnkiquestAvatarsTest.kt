// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiquestAvatarsTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = CopyOnWriteArrayList<String>()
    private val authorizations = CopyOnWriteArrayList<String>()

    @Volatile
    private var revisions = JSONObject().put("cerro", "1")

    @Volatile
    private var photoStatus = 200

    @Volatile
    private var photo = ByteArray(0)

    @Volatile
    private var posted = ByteArray(0)

    @Volatile
    private var protectedManifest = false

    @Volatile
    private var protectedPhotos = false

    @Volatile
    private var changeTokenAfterManifest = false

    @Volatile
    private var changeTokenAfterMutation = false

    @Volatile
    private var pauseManifest: Pair<CountDownLatch, CountDownLatch>? = null

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        photo = png()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add("${exchange.requestMethod} ${exchange.requestURI}")
            val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            authorizations.add(authorization)
            if (path == "/api/avatars") {
                pauseManifest?.let { (started, release) ->
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release the manifest" }
                }
            }
            if (path == "/api/avatars" && changeTokenAfterManifest) {
                AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "changed-token") }
            }
            val body =
                when {
                    exchange.requestMethod == "POST" -> {
                        posted = exchange.requestBody.readBytes()
                        "{\"revision\":\"9\"}".toByteArray()
                    }
                    exchange.requestMethod == "DELETE" -> ByteArray(0)
                    path == "/api/avatars" -> revisions.toString().toByteArray()
                    else -> photo
                }
            val status =
                when {
                    exchange.requestMethod == "GET" && authorization != "Bearer test-token" &&
                        ((path == "/api/avatars" && protectedManifest) || (path.startsWith("/api/avatar/") && protectedPhotos)) -> 401
                    exchange.requestMethod == "DELETE" -> 204
                    path == "/api/avatars" || exchange.requestMethod == "POST" -> 200
                    else -> photoStatus
                }
            if (changeTokenAfterMutation && exchange.requestMethod in listOf("POST", "DELETE")) {
                AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "changed-token") }
            }
            exchange.sendResponseHeaders(status, if (status == 204) -1 else body.size.toLong())
            exchange.responseBody.use { if (status != 204) it.write(body) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, url)
            putString(Ankiquest.USER_KEY, "cerro")
            putString(Ankiquest.TOKEN_KEY, "test-token")
        }
    }

    @After
    fun stopServer() {
        server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `account snapshot cannot pair a new token with the previous server`() {
        val preferences = targetContext.sharedPrefs()
        val changingPreferences = mockk<SharedPreferences>()

        fun switchServer() {
            preferences.edit {
                putString(Ankiquest.URL_KEY, "https://new-private.example.test")
                putString(Ankiquest.TOKEN_KEY, "new-private-token")
            }
        }
        every { changingPreferences.getString(any(), any()) } answers { preferences.getString(firstArg(), secondArg()) }
        every { changingPreferences.getString(Ankiquest.URL_KEY, "") } answers {
            val captured = preferences.getString(Ankiquest.URL_KEY, "")
            switchServer()
            captured
        }
        every { changingPreferences.all } answers {
            val captured = preferences.all
            switchServer()
            captured
        }
        AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences

        val account = assertNotNull(AnkiquestAvatars.account())
        assertEquals("$url/", account.base.toString())
        assertEquals("cerro", account.user)
        assertEquals("test-token", account.token)
    }

    @Test
    fun `private server manifest uses the configured bearer`() =
        runBlocking {
            protectedManifest = true
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals("Bearer test-token", authorizations.first())
        }

    @Test
    fun `credential change after manifest aborts further photo requests`() =
        runBlocking {
            protectedPhotos = true
            changeTokenAfterManifest = true
            assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.refresh(listOf("cerro")) }
            assertNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals(listOf("GET /api/avatars"), requests.toList())
            assertEquals(listOf("Bearer test-token"), authorizations.toList())
        }

    @Test
    fun `scope includes credentials without exposing them in identifiers or debug text`() {
        val account = assertNotNull(AnkiquestAvatars.account())
        assertTrue(account.scope.matches(Regex("[0-9a-f]{64}")))
        assertFalse(account.scope.contains(account.token))
        assertFalse(account.toString().contains(account.token))
        assertFalse(account.copy(token = "new-token").scope == account.scope)
        assertFalse(account.copy(user = "new-user").scope == account.scope)
    }

    @Test
    fun `fallback player is captured from the same preference snapshot`() {
        val preferences = targetContext.sharedPrefs()
        preferences.edit {
            remove(Ankiquest.USER_KEY)
            putString("username", "original-fallback")
        }
        val changingPreferences = mockk<SharedPreferences>()
        every { changingPreferences.all } answers {
            val snapshot = preferences.all
            preferences.edit { putString("username", "different-fallback") }
            snapshot
        }
        AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
        assertEquals("original-fallback", assertNotNull(AnkiquestAvatars.account()).user)
    }

    @Test
    fun `stale save and remove reject server player or token changes before HTTP`() =
        runBlocking {
            val captured = assertNotNull(AnkiquestAvatars.account())
            for ((key, value) in listOf(
                Ankiquest.URL_KEY to "$url/other-server",
                Ankiquest.USER_KEY to "other-player",
                Ankiquest.TOKEN_KEY to "other-token",
            )) {
                AnkiDroidApp.sharedPrefs().edit { putString(key, value) }
                assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.save(captured, createBitmap(64, 64)) }
                assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.remove(captured) }
                AnkiDroidApp.sharedPrefs().edit {
                    putString(Ankiquest.URL_KEY, url)
                    putString(Ankiquest.USER_KEY, "cerro")
                    putString(Ankiquest.TOKEN_KEY, "test-token")
                }
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `changed credential hides cached pictures and rejected read removes the old cache`() =
        runBlocking {
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "invalid-token") }
            assertNull(AnkiquestAvatars.bitmap("cerro"))
            protectedManifest = true
            assertFailsWith<Ankiquest.HttpStatusException> { AnkiquestAvatars.refresh(listOf("cerro")) }
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "test-token") }
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `a mutation queued behind refresh rechecks account after taking the mutex`() =
        runBlocking {
            val captured = assertNotNull(AnkiquestAvatars.account())
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            pauseManifest = started to release
            val refresh = async(Dispatchers.IO) { runCatching { AnkiquestAvatars.refresh(listOf("cerro"), captured) } }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                val remove = async(Dispatchers.IO) { runCatching { AnkiquestAvatars.remove(captured) } }
                AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "other-token") }
                release.countDown()
                assertTrue(refresh.await().exceptionOrNull() is AnkiquestAvatars.AccountChanged)
                assertTrue(remove.await().exceptionOrNull() is AnkiquestAvatars.AccountChanged)
                assertEquals(listOf("GET /api/avatars"), requests.toList())
            } finally {
                release.countDown()
            }
        }

    @Test
    fun `already dispatched upload stays with original credentials but cannot populate switched account cache`() =
        runBlocking {
            val captured = assertNotNull(AnkiquestAvatars.account())
            changeTokenAfterMutation = true
            assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.save(captured, createBitmap(64, 64)) }
            assertEquals(listOf("POST /api/avatar/cerro"), requests.toList())
            assertEquals(listOf("Bearer test-token"), authorizations.toList())
            assertTrue(posted.isNotEmpty(), "An already accepted HTTP request cannot be undone by a later settings edit")
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `stale queued action preserves the current account valid photo cache`() =
        runBlocking {
            val previous = assertNotNull(AnkiquestAvatars.account())
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.USER_KEY, "current-member") }
            AnkiquestAvatars.refresh(listOf("cerro"))
            val currentPhoto = assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            val sent = requests.size
            assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.remove(previous) }
            assertSame(currentPhoto, AnkiquestAvatars.bitmap("cerro"))
            assertEquals(sent, requests.size)
        }

    @Test
    fun `stale action clears photos when the configured account was removed`() =
        runBlocking {
            val previous = assertNotNull(AnkiquestAvatars.account())
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.URL_KEY) }
            assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.remove(previous) }
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.URL_KEY, url) }
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `already dispatched removal cannot republish private cache after credential change`() =
        runBlocking {
            val captured = assertNotNull(AnkiquestAvatars.account())
            AnkiquestAvatars.save(captured, createBitmap(64, 64))
            requests.clear()
            authorizations.clear()
            changeTokenAfterMutation = true
            assertFailsWith<AnkiquestAvatars.AccountChanged> { AnkiquestAvatars.remove(captured) }
            assertEquals(listOf("DELETE /api/avatar/cerro"), requests.toList())
            assertEquals(listOf("Bearer test-token"), authorizations.toList())
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `public server without a token receives no authorization header`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.TOKEN_KEY) }
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals(listOf("", ""), authorizations.toList())
        }

    @Test
    fun `same revision reuses cache while replacement and removal update it`() =
        runBlocking {
            AnkiquestAvatars.refresh(listOf("cerro"))
            val first = assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals(64, first.width)
            assertEquals(64, first.height)
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertSame(first, AnkiquestAvatars.bitmap("cerro"))
            assertEquals(1, requests.count { it.startsWith("GET /api/avatar/") })

            revisions.put("cerro", "2")
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotSame(first, assertNotNull(AnkiquestAvatars.bitmap("cerro")))
            assertTrue(requests.contains("GET /api/avatar/cerro?v=2"))
            revisions.remove("cerro")
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `broken replacement falls back rather than showing an outdated picture`(): Unit =
        runBlocking {
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            revisions.put("cerro", "2")
            photo = "not an image".toByteArray()
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNull(AnkiquestAvatars.bitmap("cerro"))
            photo = png()
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `account and server changes cannot display cached pictures from another scope`() =
        runBlocking {
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.USER_KEY, "other") }
            assertNull(AnkiquestAvatars.bitmap("cerro"))
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals(2, requests.count { it.startsWith("GET /api/avatar/") })
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.URL_KEY, "$url/another-server") }
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `upload and remove authenticate the captured owner and clear local photo`() =
        runBlocking {
            val account = assertNotNull(AnkiquestAvatars.account())
            AnkiquestAvatars.save(account, createBitmap(256, 256))
            assertNotNull(AnkiquestAvatars.bitmap("cerro"))
            assertTrue(requests.contains("POST /api/avatar/cerro"))
            assertTrue(posted.isNotEmpty())
            assertEquals("Bearer test-token", authorizations.last())
            AnkiquestAvatars.remove(account)
            assertNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals("DELETE /api/avatar/cerro", requests.last())
            assertEquals("Bearer test-token", authorizations.last())
        }

    @Test
    fun `missing token prevents mutations and user paths stay encoded`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit {
                putString(Ankiquest.USER_KEY, "a/b ?")
                remove(Ankiquest.TOKEN_KEY)
            }
            val account = assertNotNull(AnkiquestAvatars.account())
            assertTrue(account.url().encodedPath.endsWith("/a%2Fb%20%3F"))
            assertFailsWith<IllegalStateException> { AnkiquestAvatars.remove(account) }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `malformed revisions and oversized downloads use initials`() =
        runBlocking {
            revisions.put("cerro", "../../elsewhere")
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNull(AnkiquestAvatars.bitmap("cerro"))
            assertEquals(1, requests.size)
            revisions.put("cerro", "1")
            photo = ByteArray(1024 * 1024 + 1)
            AnkiquestAvatars.refresh(listOf("cerro"))
            assertNull(AnkiquestAvatars.bitmap("cerro"))
        }

    @Test
    fun `failed early users cannot starve later pictures on subsequent refresh`() =
        runBlocking {
            val users = (1..14).map { "person$it" }
            revisions = JSONObject(users.associateWith { "1" })
            photoStatus = 404
            AnkiquestAvatars.refresh(users)
            assertEquals(12, requests.count { it.startsWith("GET /api/avatar/") })
            AnkiquestAvatars.refresh(users)
            assertTrue(requests.any { it.startsWith("GET /api/avatar/person14?") })
        }

    @Test
    fun `widget styles show cached photos then return to centered initials after removal`() =
        runBlocking {
            val board = JSONArray().put(JSONObject().put("user", "cerro").put("display", "Cerro").put("level", 1))
            AnkiquestAvatars.refresh(listOf("cerro"))
            for (style in AnkiquestWidget.styles) {
                val row =
                    AnkiquestWidget
                        .collection(
                            targetContext,
                            board,
                            style,
                        ).getItemView(0)
                        .apply(targetContext, FrameLayout(targetContext))
                assertEquals(View.VISIBLE, row.findViewById<ImageView>(R.id.ankiquest_widget_avatar).visibility)
                assertEquals(View.GONE, row.findViewById<TextView>(R.id.ankiquest_widget_initial).visibility)
            }
            revisions.remove("cerro")
            AnkiquestAvatars.refresh(listOf("cerro"))
            for (style in AnkiquestWidget.styles) {
                val row =
                    AnkiquestWidget
                        .collection(
                            targetContext,
                            board,
                            style,
                        ).getItemView(0)
                        .apply(targetContext, FrameLayout(targetContext))
                val initial = row.findViewById<TextView>(R.id.ankiquest_widget_initial)
                assertEquals(View.GONE, row.findViewById<ImageView>(R.id.ankiquest_widget_avatar).visibility)
                assertEquals(View.VISIBLE, initial.visibility)
                assertEquals("C", initial.text.toString())
                assertEquals(Gravity.CENTER, initial.gravity)
                assertTrue(!initial.includeFontPadding)
            }
        }

    @Test
    fun `valid pictures become square and invalid files are rejected`() {
        val result = AnkiquestAvatars.decodePhoto(png(), 256)
        assertEquals(256, result.width)
        assertEquals(256, result.height)
        assertFailsWith<IllegalArgumentException> { AnkiquestAvatars.decodePhoto("bad".toByteArray(), 256) }
    }

    @Test
    fun `picked photos retain their full aspect ratio until the user chooses a crop`() =
        runBlocking {
            val file = File.createTempFile("avatar-crop-", ".png", targetContext.cacheDir)
            try {
                file.writeBytes(png())
                val prepared = AnkiquestAvatars.prepare(targetContext, file.toUri())
                assertEquals(8, prepared.width)
                assertEquals(4, prepared.height)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `all EXIF orientations preserve the intended corner colors`() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected =
            listOf(
                listOf(0, 1, 2, 3),
                listOf(1, 0, 3, 2),
                listOf(3, 2, 1, 0),
                listOf(2, 3, 0, 1),
                listOf(0, 2, 1, 3),
                listOf(2, 0, 3, 1),
                listOf(3, 1, 2, 0),
                listOf(1, 3, 0, 2),
            )
        val source = createBitmap(32, 32)
        for (y in 0 until 32) {
            for (x in 0 until 32) source[x, y] = colors[(if (y >= 16) 2 else 0) + (if (x >= 16) 1 else 0)]
        }
        for (orientation in 1..8) {
            val file = File.createTempFile("avatar-orientation-", ".jpg", targetContext.cacheDir)
            try {
                file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val decoded = AnkiquestAvatars.decodePhoto(file.readBytes(), 32)
                val actual =
                    listOf(8 to 8, 24 to 8, 8 to 24, 24 to 24).map { (x, y) ->
                        val pixel = decoded[x, y]
                        colors.indices.minBy { index ->
                            val color = colors[index]
                            kotlin.math.abs(Color.red(pixel) - Color.red(color)) +
                                kotlin.math.abs(Color.green(pixel) - Color.green(color)) +
                                kotlin.math.abs(Color.blue(pixel) - Color.blue(color))
                        }
                    }
                assertEquals(expected[orientation - 1], actual, "EXIF orientation $orientation")
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `picture chooser accepts explicit and default ports of the same origin`() {
        for ((scheme, port) in listOf("https" to 443, "http" to 80)) {
            val explicit = "$scheme://example.test:$port/profile".toUri()
            val implicit = "$scheme://example.test/settings".toUri()
            assertTrue(AnkiquestActivity.acceptsPictureOrigin(implicit, explicit), "$scheme explicit configured port")
            assertTrue(AnkiquestActivity.acceptsPictureOrigin(explicit, implicit), "$scheme implicit configured port")
        }
    }

    @Test
    fun `picture chooser rejects different origins and absent pages`() {
        val base = "https://example.test/profile".toUri()
        for (url in listOf("http://example.test/profile", "https://example.test:8443/profile", "https://other.test/profile")) {
            assertFalse(AnkiquestActivity.acceptsPictureOrigin(url.toUri(), base), url)
        }
        assertFalse(AnkiquestActivity.acceptsPictureOrigin(null, base))
    }

    private fun png(): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                createBitmap(8, 4)
                    .apply { eraseColor(Color.BLUE) }
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()
}
