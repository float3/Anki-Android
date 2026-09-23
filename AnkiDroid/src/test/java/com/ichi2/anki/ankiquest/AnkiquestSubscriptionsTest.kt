// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestSubscriptionsTest : RobolectricTest() {
    private lateinit var server: HttpServer

    @Volatile private lateinit var account: HomeAccount
    private lateinit var repository: SubscriptionRepository
    private val requests = CopyOnWriteArrayList<Triple<String, String, String?>>()
    private val writes = CopyOnWriteArrayList<JSONObject>()

    @Volatile private var settings = ""

    @Volatile private var status = 200

    @Volatile private var postStatus = 200

    @Volatile private var redirect: String? = null

    @Volatile private var pauseNextGet = false
    private val getStarted = CountDownLatch(1)
    private val releaseGet = CountDownLatch(1)

    @Volatile private var changeAccountDuringRequest = false

    @Volatile private var changeAccountDuringWrite = false

    @Before
    fun prepare() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        account = HomeAccount("http://127.0.0.1:${server.address.port}/quest", "member name", "secret")
        repository = SubscriptionRepository { account }
        settings =
            """{"enabled":true,"muted_senders":["old"],"unsubscribed_senders":[],"sharing_senders":["friend"],"senders":[{"user":"friend","display":"Friend"},{"user":"old","display":"Former player"},{"user":"unrelated","display":"Another member"}]}"""
        server.createContext("/") { exchange ->
            requests += Triple(exchange.requestMethod, exchange.requestURI.toString(), exchange.requestHeaders.getFirst("Authorization"))
            if (exchange.requestMethod == "GET" && pauseNextGet) {
                pauseNextGet = false
                getStarted.countDown()
                check(releaseGet.await(10, TimeUnit.SECONDS)) { "The test did not release its paused request" }
            }
            val responseStatus = if (exchange.requestMethod == "POST") postStatus else status
            if (exchange.requestMethod == "POST") {
                val update = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                writes += update
                if (responseStatus == 200) {
                    settings =
                        JSONObject(settings)
                            .put("enabled", update.getBoolean("enabled"))
                            .put(
                                "muted_senders",
                                org.json.JSONArray(),
                            ).put("unsubscribed_senders", update.getJSONArray("unsubscribed_senders"))
                            .toString()
                }
            }
            if (changeAccountDuringRequest) account = account.copy(token = "new-account-token")
            if (changeAccountDuringWrite && exchange.requestMethod == "POST") account = account.copy(user = "another-player")
            val bytes = settings.toByteArray()
            redirect?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        releaseGet.countDown()
        server.stop(0)
    }

    @Test
    fun `incoming settings are authenticated and preserve server prefix and encoded user`() =
        runBlocking {
            val result = repository.load(account)
            assertTrue(result.enabled)
            assertEquals(setOf("old"), result.unsubscribedSenders)
            assertEquals(listOf("old", "friend"), result.senders.map { it.user })
            assertEquals(
                Triple<String, String, String?>("GET", "/quest/api/deck-subscriptions/member%20name", "Bearer secret"),
                requests.single(),
            )
        }

    @Test
    fun `unsubscribe and resubscribe preserve other mutes and the global switch`() =
        runBlocking {
            settings = JSONObject(settings).put("enabled", false).toString()
            val muted = repository.setSubscribed(account, "friend", false)
            assertFalse(muted.enabled)
            assertEquals(setOf("old", "friend"), muted.unsubscribedSenders)
            val resumed = repository.setSubscribed(account, "friend", true)
            assertFalse(resumed.enabled)
            assertEquals(setOf("old"), resumed.unsubscribedSenders)
            assertTrue(writes.all { it.keys().asSequence().toSet() == setOf("enabled", "unsubscribed_senders") })
            assertTrue(requests.all { it.second.contains("deck-subscriptions") })
        }

    @Test
    fun `global switch keeps sender mutes and reads changes made elsewhere before saving`() =
        runBlocking {
            repository.load(account)
            settings =
                JSONObject(settings)
                    .put(
                        "muted_senders",
                        org.json
                            .JSONArray()
                            .put("friend")
                            .put("old"),
                    ).toString()
            val saved = repository.setEnabled(account, false)
            assertFalse(saved.enabled)
            assertEquals(setOf("friend", "old"), saved.unsubscribedSenders)
            assertEquals(listOf("GET", "GET", "POST"), requests.map { it.first })
        }

    @Test
    fun `missing token and stale account do not send requests`() =
        runBlocking {
            val original = account
            account = account.copy(token = "")
            assertFailsWith<SubscriptionHttpException> { repository.load(account) }
            assertFailsWith<HomeAccountChanged> { repository.setSubscribed(original, "friend", false) }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `account change while fetching prevents mutation and discards old account data`() =
        runBlocking {
            changeAccountDuringRequest = true
            assertFailsWith<HomeAccountChanged> { repository.setSubscribed(account, "friend", false) }
            assertEquals(listOf("GET"), requests.map { it.first })
            assertTrue(writes.isEmpty())
        }

    @Test
    fun `account change during a sent write cannot present its result under the new account`() =
        runBlocking {
            changeAccountDuringWrite = true
            assertFailsWith<HomeAccountChanged> { repository.setSubscribed(account, "friend", false) }
            assertEquals(listOf("GET", "POST"), requests.map { it.first })
            assertTrue(requests.all { it.second.endsWith("member%20name") && it.third == "Bearer secret" })
        }

    @Test
    fun `unsupported server is surfaced and cannot receive writes`() =
        runBlocking {
            status = 404
            val error = assertFailsWith<SubscriptionHttpException> { repository.setEnabled(account, false) }
            assertEquals(404, error.code)
            assertTrue(writes.isEmpty())
        }

    @Test
    fun `unrelated roster member cannot be silently subscribed or unsubscribed`() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { repository.setSubscribed(account, "unrelated", false) }
            assertTrue(writes.isEmpty())
        }

    @Test
    fun `retained mutes stay editable even when sender is omitted from roster`() {
        val result =
            IncomingSubscriptions.parse(
                JSONObject("""{"enabled":true,"muted_senders":["former"],"unsubscribed_senders":[],"sharing_senders":[],"senders":[]}"""),
            )
        assertEquals(listOf(IncomingSender("former", "former")), result.senders)
    }

    @Test
    fun `current sharing and recipient unsubscribes are shown but other server members are omitted`() {
        val result =
            IncomingSubscriptions.parse(
                JSONObject(
                    """{"enabled":true,"muted_senders":[],"unsubscribed_senders":["former"],"sharing_senders":["friend"],"senders":[{"user":"friend","display":"Friend"},{"user":"former","display":"Former"},{"user":"unrelated","display":"Another member"}]}""",
                ),
            )
        assertEquals(setOf("friend", "former"), result.senders.map { it.user }.toSet())
        assertEquals(setOf("former"), result.unsubscribedSenders)
    }

    @Test
    fun `missing sharing metadata cannot be mistaken for supported recipient owned subscriptions`() =
        runBlocking {
            settings = JSONObject(settings).also { it.remove("sharing_senders") }.toString()
            assertFailsWith<org.json.JSONException> { repository.setEnabled(account, false) }
            assertTrue(writes.isEmpty())
        }

    @Test
    fun `redirect is reported without forwarding credentials to its target`() =
        runBlocking {
            status = 302
            redirect = "http://127.0.0.1:${server.address.port}/redirect-target"
            val error = assertFailsWith<SubscriptionHttpException> { repository.load(account) }
            assertEquals(302, error.code)
            assertEquals(1, requests.size)
            assertFalse(requests.any { it.second.contains("redirect-target") })
        }

    @Test
    fun `cancelling during fresh read prevents the queued write`() =
        runBlocking {
            pauseNextGet = true
            val pending = async(Dispatchers.Default) { repository.setSubscribed(account, "friend", false) }
            try {
                assertTrue(getStarted.await(5, TimeUnit.SECONDS), "The mutation must begin by reading current subscriptions")
                pending.cancel()
            } finally {
                releaseGet.countDown()
            }
            withTimeout(5000) { pending.join() }
            assertEquals(listOf("GET"), requests.map { it.first })
            assertTrue(writes.isEmpty())
        }

    @Test
    fun `a successful read cannot hide rejected or failed writes`() =
        runBlocking {
            for (code in listOf(401, 500)) {
                postStatus = code
                val error = assertFailsWith<SubscriptionHttpException> { repository.setSubscribed(account, "friend", false) }
                assertEquals(code, error.code)
            }
            assertEquals(listOf("GET", "POST", "GET", "POST"), requests.map { it.first })
            assertEquals(setOf("old"), IncomingSubscriptions.parse(JSONObject(settings)).unsubscribedSenders)
        }

    @Test
    fun `concurrent local changes keep both selections`() =
        runBlocking {
            listOf(
                async(Dispatchers.Default) { repository.setSubscribed(account, "friend", false) },
                async(Dispatchers.Default) { repository.setSubscribed(account, "old", true) },
            ).awaitAll()
            assertEquals(listOf("GET", "POST", "GET", "POST"), requests.map { it.first })
            assertEquals(setOf("friend"), IncomingSubscriptions.parse(JSONObject(settings)).unsubscribedSenders)
        }
}
