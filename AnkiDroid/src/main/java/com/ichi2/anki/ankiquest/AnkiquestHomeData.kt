// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A captured identity: queued work must never borrow credentials from changed preferences. */
internal data class HomeAccount(
    val server: String,
    val user: String,
    val token: String,
    val language: String = Locale.getDefault().toLanguageTag(),
) {
    val encodedUser: String = URLEncoder.encode(user, "UTF-8").replace("+", "%20")
    val notificationAccount: String = "$server/$encodedUser"
    val scope: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$server\u0000$user\u0000$token".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun url(path: String): HttpUrl = requireNotNull("$server/$path".toHttpUrlOrNull())

    override fun toString(): String = "HomeAccount(server=$server, user=$user)"

    companion object {
        fun from(
            settings: Map<String, *>,
            fallbackUser: String,
        ): HomeAccount? {
            val server = (settings[Ankiquest.URL_KEY] as? String).orEmpty().trim().trimEnd('/')
            val user = (settings[Ankiquest.USER_KEY] as? String).orEmpty().trim().ifEmpty { fallbackUser.trim() }
            val token = (settings[Ankiquest.TOKEN_KEY] as? String).orEmpty().trim()
            val parsed = server.toHttpUrlOrNull() ?: return null
            if (user.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null ||
                parsed.fragment != null
            ) {
                return null
            }
            val language = (settings["language"] as? String)?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().toLanguageTag()
            return HomeAccount(server, user, token, language)
        }
    }
}

internal data class HomeDeck(
    val id: Long,
    val name: String,
    val new: Int,
    val learning: Int,
    val review: Int,
) {
    val due: Int get() = new + learning + review
}

internal data class HomeLocal(
    val decks: List<HomeDeck>,
    val selected: Long,
) {
    val focus: HomeDeck? get() =
        decks.firstOrNull { it.id == selected && it.due > 0 } ?: decks.firstOrNull { it.due > 0 }
            ?: decks.firstOrNull { it.id == selected }
            ?: decks.firstOrNull()
}

internal object AnkiquestHomeData {
    fun account(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): HomeAccount? {
        val settings = preferences.all
        return HomeAccount.from(settings, (settings["username"] as? String).orEmpty())
    }

    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /** The profile of the captured [account], so changed settings never borrow its credentials. */
    suspend fun profile(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(account.url("api/profile/${account.encodedUser}"))
                    .header("Accept-Language", account.language)
                    .apply { if (account.token.isNotEmpty()) header("Authorization", "Bearer ${account.token}") }
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Ankiquest.HttpStatusException(response.code)
                JSONObject(response.body.string())
            }
        }

    suspend fun local(): HomeLocal =
        CollectionManager.withCol {
            val rows =
                sched.deckDueTree().filter { it.did != 0L }.map {
                    HomeDeck(it.did, it.fullDeckName, it.newCount, it.lrnCount, it.revCount)
                }
            HomeLocal(rows, decks.selected())
        }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
