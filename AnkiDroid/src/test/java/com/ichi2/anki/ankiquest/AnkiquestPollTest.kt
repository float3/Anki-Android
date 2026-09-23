// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestPollTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile
    private var inboxStatus = 200

    @Volatile
    private var leaderboardStatus = 200

    @Volatile
    private var avatarStatus = 200

    @Volatile
    private var switchAccountDuringAvatars = false

    @Volatile
    private var replaceBoardDuringAvatars = false

    @Volatile
    private var inboxBody = "[]"

    @Volatile
    private var switchAccountDuringInbox = false

    @Before
    fun startPollingServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        mockkObject(AnkiquestWidget, AnkiquestNotifier)
        every { AnkiquestWidget.render(any(), any(), any(), any()) } just Runs
        coEvery { AnkiquestNotifier.onLeaderboard(any()) } just Runs
        every { AnkiquestNotifier.onProfile(any(), any()) } just Runs
        every { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) } just Runs

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            if (path == "/api/avatars" && switchAccountDuringAvatars) {
                AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.USER_KEY, "other") }
            }
            if (path == "/api/avatars" && replaceBoardDuringAvatars) {
                AnkiDroidApp.sharedPrefs().edit {
                    putLong("ankiquestWidgetLeaderboardAt", AnkiDroidApp.sharedPrefs().getLong("ankiquestWidgetLeaderboardAt", 0) + 1)
                    putString("ankiquestWidgetLeaderboard", "[{\"user\":\"newer\"}]")
                }
            }
            val inbox = path.startsWith("/api/notifications/")
            if (inbox && switchAccountDuringInbox) {
                AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.USER_KEY, "other") }
            }
            val status =
                when {
                    inbox -> inboxStatus
                    path == "/api/leaderboard" -> leaderboardStatus
                    path == "/api/avatars" -> avatarStatus
                    else -> 200
                }
            val response =
                when {
                    inbox -> inboxBody
                    path == "/api/leaderboard" -> "[]"
                    else -> "{}"
                }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, url)
            putString(Ankiquest.USER_KEY, "cerro")
            putString(Ankiquest.TOKEN_KEY, "test-token")
            remove("ankiquestWidgetLeaderboard")
        }
    }

    @After
    fun stopPollingServer() {
        if (::server.isInitialized) server.stop(0)
        unmockkObject(AnkiquestWidget, AnkiquestNotifier)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `widget rendering failure does not prevent inbox delivery`() =
        runBlocking {
            every { AnkiquestWidget.render(any(), any(), any(), any()) } throws IllegalStateException("Widget unavailable")

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 1) { AnkiquestNotifier.onDeckCompletions(any(), "$url/cerro", any(), any()) }
        }

    @Test
    fun `corrupt cached leaderboard does not prevent inbox delivery`() =
        runBlocking {
            leaderboardStatus = 503
            AnkiDroidApp.sharedPrefs().edit { putString("ankiquestWidgetLeaderboard", "invalid JSON") }

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 1) { AnkiquestNotifier.onDeckCompletions(any(), "$url/cerro", any(), any()) }
        }

    @Test
    fun `leaderboard notification failure does not prevent inbox delivery`() =
        runBlocking {
            coEvery { AnkiquestNotifier.onLeaderboard(any()) } throws JSONException("Incomplete leaderboard")

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 1) { AnkiquestNotifier.onDeckCompletions(any(), "$url/cerro", any(), any()) }
        }

    @Test
    fun `temporary inbox server failure requests worker retry`() =
        runBlocking {
            inboxStatus = 503

            assertEquals(Result.retry(), worker().doWork())

            verify(exactly = 0) { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) }
        }

    @Test
    fun `inbox rate limiting requests worker retry`() =
        runBlocking {
            inboxStatus = 429

            assertEquals(Result.retry(), worker().doWork())
        }

    @Test
    fun `inbox request timeout requests worker retry`() =
        runBlocking {
            inboxStatus = 408

            assertEquals(Result.retry(), worker().doWork())
        }

    @Test
    fun `inbox network failure requests worker retry`() =
        runBlocking {
            server.stop(0)

            assertEquals(Result.retry(), worker().doWork())
        }

    @Test
    fun `invalid credentials and unsupported inbox do not request network retry`() =
        runBlocking {
            for (status in listOf(401, 403, 404)) {
                inboxStatus = status
                assertEquals(Result.success(), worker().doWork(), "HTTP $status needs settings or server support")
            }
            verify(exactly = 0) { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) }
        }

    @Test
    fun `malformed inbox payload does not request network retry or deliver messages`() =
        runBlocking {
            inboxBody = "invalid JSON"

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 0) { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) }
        }

    @Test
    fun `missing token skips inbox and succeeds without retry`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.TOKEN_KEY) }

            assertEquals(Result.success(), worker().doWork())

            assertTrue(requests.none { it.startsWith("/api/notifications/") })
            verify(exactly = 0) { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) }
        }

    @Test
    fun `inbox response retains its captured account when settings change`() =
        runBlocking {
            val expectedScope = requireNotNull(AnkiquestHomeData.account()).scope
            switchAccountDuringInbox = true

            assertEquals(Result.success(), worker().doWork())

            assertEquals("other", Ankiquest.player())
            verify(exactly = 1) { AnkiquestNotifier.onDeckCompletions(any(), "$url/cerro", any(), expectedScope) }
            verify(exactly = 0) { AnkiquestNotifier.onDeckCompletions(any(), "$url/other", any(), any()) }
            assertTrue(requests.none { it == "/api/avatars" })
        }

    @Test
    fun `cancellation during profile work is propagated before fetching inbox`() =
        runBlocking {
            every { AnkiquestNotifier.onProfile(any(), any()) } throws CancellationException("Worker stopped")

            assertFailsWith<CancellationException> { worker().doWork() }

            assertTrue(requests.none { it.startsWith("/api/notifications/") })
        }

    @Test
    fun `cancellation during inbox delivery is propagated`(): Unit =
        runBlocking {
            every { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) } throws CancellationException("Worker stopped")

            assertFailsWith<CancellationException> { worker().doWork() }
        }

    @Test
    fun `photo failure happens after inbox delivery and cannot request retry`() =
        runBlocking {
            val expectedScope = requireNotNull(AnkiquestHomeData.account()).scope
            avatarStatus = 503

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 1) { AnkiquestNotifier.onDeckCompletions(any(), "$url/cerro", any(), expectedScope) }
            val inbox = requests.indexOfFirst { it.startsWith("/api/notifications/") }
            assertTrue(inbox >= 0)
            assertTrue(requests.indexOf("/api/avatars") > inbox)
        }

    @Test
    fun `account change during optional photos cannot redraw the old board`() =
        runBlocking {
            switchAccountDuringAvatars = true

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 1) { AnkiquestWidget.render(any(), any(), any(), any()) }
        }

    @Test
    fun `older photo fetch cannot overwrite a newer cached leaderboard`() =
        runBlocking {
            replaceBoardDuringAvatars = true

            assertEquals(Result.success(), worker().doWork())

            verify(exactly = 1) { AnkiquestWidget.render(any(), any(), any(), any()) }
        }

    private fun worker(): AnkiquestPollWorker = TestListenableWorkerBuilder<AnkiquestPollWorker>(targetContext).build()
}
