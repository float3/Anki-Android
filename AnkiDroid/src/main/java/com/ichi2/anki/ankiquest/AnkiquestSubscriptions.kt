// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class IncomingSender(
    val user: String,
    val display: String,
)

internal data class IncomingSubscriptions(
    val enabled: Boolean,
    val unsubscribedSenders: Set<String>,
    val senders: List<IncomingSender>,
    val legacyMutes: Boolean = false,
) {
    companion object {
        fun parse(json: JSONObject): IncomingSubscriptions {
            fun strings(key: String): Set<String> =
                json.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            val unsubscribed = strings("unsubscribed_senders") + strings("muted_senders")
            val visible = strings("sharing_senders") + unsubscribed
            val senders =
                json.getJSONArray("senders").objects().map {
                    val user = it.getString("user")
                    IncomingSender(user, it.getString("display").ifBlank { user })
                }
            // Roster identities alone do not mean someone shares a deck with this recipient.
            val retained = visible.filter { user -> senders.none { it.user == user } }.map { IncomingSender(it, it) }
            return IncomingSubscriptions(
                json.getBoolean("enabled"),
                unsubscribed,
                (senders + retained)
                    .filter { it.user in visible }
                    .distinctBy { it.user }
                    .sortedWith(compareBy<IncomingSender> { it.display.lowercase(java.util.Locale.ROOT) }.thenBy { it.user }),
                strings("muted_senders").isNotEmpty(),
            )
        }
    }
}

internal class HomeAccountChanged : IOException("The AnkiQuest account changed")

internal class SubscriptionHttpException(
    val code: Int,
) : IOException("HTTP $code")

/** Captures credentials once and keeps reads and writes ordered across recreated settings screens. */
internal class SubscriptionRepository(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    private val accountProvider: () -> HomeAccount?,
) {
    private val mutex = Mutex()
    private val json = "application/json".toMediaType()

    private fun requireCurrent(account: HomeAccount) {
        if (accountProvider()?.scope != account.scope) throw HomeAccountChanged()
        if (account.token.isEmpty()) throw SubscriptionHttpException(401)
    }

    private fun request(
        account: HomeAccount,
        body: JSONObject? = null,
    ): IncomingSubscriptions {
        requireCurrent(account)
        val request =
            Request
                .Builder()
                .url(account.url("api/deck-subscriptions/${account.encodedUser}"))
                .header("Authorization", "Bearer ${account.token}")
                .apply { if (body != null) post(body.toString().toRequestBody(json)) }
                .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string()
            requireCurrent(account)
            if (!response.isSuccessful) throw SubscriptionHttpException(response.code)
            IncomingSubscriptions.parse(JSONObject(text))
        }
    }

    suspend fun load(account: HomeAccount): IncomingSubscriptions =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                request(account)
            }
        }

    suspend fun setEnabled(
        account: HomeAccount,
        enabled: Boolean,
    ): IncomingSubscriptions = update(account) { it.copy(enabled = enabled) }

    suspend fun setSubscribed(
        account: HomeAccount,
        user: String,
        subscribed: Boolean,
    ): IncomingSubscriptions =
        update(account) { current ->
            require(current.senders.any { it.user == user }) { "The sender is no longer available" }
            current.copy(unsubscribedSenders = if (subscribed) current.unsubscribedSenders - user else current.unsubscribedSenders + user)
        }

    private suspend fun update(
        account: HomeAccount,
        change: (IncomingSubscriptions) -> IncomingSubscriptions,
    ): IncomingSubscriptions =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                // Full replacement: preserve already-observed remote changes. Simultaneous remote writes can still race.
                val wanted = change(request(account))
                currentCoroutineContext().ensureActive()
                request(
                    account,
                    JSONObject().put("enabled", wanted.enabled).put("unsubscribed_senders", JSONArray(wanted.unsubscribedSenders.sorted())),
                )
            }
        }
}

internal object AnkiquestSubscriptions {
    val repository = SubscriptionRepository { AnkiquestHomeData.account() }
}
