// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.utils.openInputStreamSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Optional, bounded photos. Nothing in this cache is needed to fetch scores or private messages. */
object AnkiquestAvatars {
    private const val MAX_IMAGE_BYTES = 1024 * 1024
    private const val MAX_PICKED_BYTES = 10 * 1024 * 1024
    private const val MAX_CACHED = 100
    private const val MAX_DOWNLOADS = 12
    private val mutex = Mutex()
    private val revisionFormat = Regex("[0-9]{1,20}")
    private var nextDownload = 0
    private val client =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()

    data class Account(
        val base: HttpUrl,
        val user: String,
        val token: String,
    ) {
        // The identity may be compared or saved without exposing its credential.
        val scope: String =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$base\u0000$user\u0000$token".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        override fun toString(): String = "AvatarAccount(base=$base, user=$user)"

        fun url(player: String = user): HttpUrl =
            base
                .newBuilder()
                .addPathSegments("api/avatar")
                .addPathSegment(player)
                .build()
    }

    private data class Photo(
        val revision: String,
        val bitmap: Bitmap,
    )

    private data class Cache(
        val scope: String,
        val photos: Map<String, Photo>,
    )

    @Volatile
    private var cached = Cache("", emptyMap())

    fun account(): Account? {
        // Keep the server and its credentials together if settings change during a refresh.
        val settings = AnkiDroidApp.sharedPrefs().all
        val url = (settings[Ankiquest.URL_KEY] as? String).orEmpty().trim().trimEnd('/')
        // username is R.string.username_key. Read the fallback from this same snapshot.
        val user =
            (settings[Ankiquest.USER_KEY] as? String)
                .orEmpty()
                .trim()
                .ifEmpty { (settings["username"] as? String).orEmpty().trim() }
        val token = (settings[Ankiquest.TOKEN_KEY] as? String).orEmpty().trim()
        if (url.isEmpty() || user.isEmpty()) return null
        val base = "$url/".toHttpUrlOrNull() ?: return null
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null) return null
        return Account(base, user, token)
    }

    class AccountChanged : IllegalStateException("Your AnkiQuest account changed. Open profile pictures again.")

    fun requireCurrent(captured: Account) {
        if (account()?.scope != captured.scope) throw AccountChanged()
    }

    /** Caller holds mutex. Preferences can still change while a request is in flight. */
    private fun requireCurrentLocked(captured: Account) {
        val currentScope = account()?.scope
        if (currentScope != captured.scope) {
            if (currentScope == null || cached.scope != currentScope) {
                cached = Cache("", emptyMap())
                nextDownload = 0
            }
            throw AccountChanged()
        }
    }

    private fun requireResponse(
        captured: Account,
        response: Response,
    ) {
        requireCurrentLocked(captured)
        if (!response.isSuccessful) {
            if (response.code == 401 || response.code == 403) cached = Cache("", emptyMap())
            throw Ankiquest.HttpStatusException(response.code)
        }
    }

    fun bitmap(user: String): Bitmap? {
        val account = runCatching { account() }.getOrNull() ?: return null
        val snapshot = cached
        return snapshot.photos[user]?.bitmap.takeIf { snapshot.scope == account.scope }
    }

    /** A successful manifest immediately drops removed/replaced photos, even if downloading fails. */
    suspend fun refresh(users: List<String>) = refresh(users, account())

    internal suspend fun refresh(
        users: List<String>,
        expected: Account?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account =
                expected ?: run {
                    cached = Cache("", emptyMap())
                    nextDownload = 0
                    return@withLock
                }
            requireCurrentLocked(account)
            if (cached.scope != account.scope) {
                cached = Cache(account.scope, emptyMap())
                nextDownload = 0
            }
            val manifest =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(
                                account.base
                                    .newBuilder()
                                    .addPathSegments("api/avatars")
                                    .build(),
                            ).apply {
                                if (account.token.isNotEmpty()) header("Authorization", "Bearer ${account.token}")
                            }.build(),
                    ).execute()
                    .use { response ->
                        requireResponse(account, response)
                        JSONObject(response.body.byteStream().use { it.boundedBytes(256 * 1024).toString(Charsets.UTF_8) })
                    }
            requireCurrentLocked(account)
            if (cached.scope != account.scope) nextDownload = 0
            val previous = cached.takeIf { it.scope == account.scope }?.photos.orEmpty()
            val wanted =
                users
                    .distinct()
                    .take(MAX_CACHED)
                    .mapNotNull { user ->
                        val revision = manifest.opt(user) as? String
                        if (revision != null && revisionFormat.matches(revision)) user to revision else null
                    }.toMap()
            val photos = previous.filter { (user, photo) -> wanted[user] == photo.revision }.toMutableMap()
            cached = Cache(account.scope, photos.toMap())
            val pending = wanted.filterKeys { it !in photos }.entries.toList()
            if (pending.isEmpty()) return@withLock
            val start = nextDownload % pending.size
            val attempts = minOf(MAX_DOWNLOADS, pending.size)
            nextDownload = (start + attempts) % pending.size
            for (offset in 0 until attempts) {
                currentCoroutineContext().ensureActive()
                requireCurrentLocked(account)
                val (user, revision) = pending[(start + offset) % pending.size]
                try {
                    val url =
                        account
                            .url(user)
                            .newBuilder()
                            .addQueryParameter("v", revision)
                            .build()
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .apply {
                                if (account.token.isNotEmpty()) header("Authorization", "Bearer ${account.token}")
                            }.build()
                    val bitmap =
                        client.newCall(request).execute().use { response ->
                            requireResponse(account, response)
                            decodePhoto(response.body.byteStream().use { it.boundedBytes(MAX_IMAGE_BYTES) }, 64)
                        }
                    requireCurrentLocked(account)
                    photos[user] = Photo(revision, circle(bitmap))
                    cached = Cache(account.scope, photos.toMap())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountChanged) {
                    throw e
                } catch (e: Ankiquest.HttpStatusException) {
                    if (e.code == 401 || e.code == 403) throw e
                    Timber.d(e, "ankiquest optional photo unavailable")
                } catch (e: Exception) {
                    Timber.d(e, "ankiquest optional photo unavailable")
                }
            }
        }
    }

    suspend fun prepare(
        context: Context,
        uri: Uri,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val bytes =
                context.contentResolver.openInputStreamSafe(uri)?.use { it.boundedBytes(MAX_PICKED_BYTES) }
                    ?: throw IOException("Could not open this picture.")
            decodePhoto(bytes, 256)
        }

    suspend fun save(
        account: Account,
        bitmap: Bitmap,
    ) = withContext(Dispatchers.IO) {
        requireToken(account)
        requireCurrent(account)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        require(bytes.size <= MAX_IMAGE_BYTES) { "This picture is too large." }
        mutex.withLock {
            requireCurrentLocked(account)
            client
                .newCall(
                    Request
                        .Builder()
                        .url(account.url())
                        .header("Authorization", "Bearer ${account.token}")
                        .post(bytes.toRequestBody("image/png".toMediaType()))
                        .build(),
                ).execute()
                .use { response ->
                    requireResponse(account, response)
                    val revision =
                        JSONObject(
                            response.body.byteStream().use { it.boundedBytes(4096).toString(Charsets.UTF_8) },
                        ).getString("revision")
                    check(revisionFormat.matches(revision)) { "Invalid profile picture revision." }
                    requireCurrentLocked(account)
                    val photos =
                        cached
                            .takeIf { it.scope == account.scope }
                            ?.photos
                            .orEmpty()
                            .toMutableMap()
                    photos[account.user] = Photo(revision, circle(bitmap.scale(64, 64)))
                    cached = Cache(account.scope, photos.toMap())
                }
        }
    }

    suspend fun remove(account: Account) =
        withContext(Dispatchers.IO) {
            requireToken(account)
            mutex.withLock {
                requireCurrentLocked(account)
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(account.url())
                            .header("Authorization", "Bearer ${account.token}")
                            .delete()
                            .build(),
                    ).execute()
                    .use { response ->
                        requireResponse(account, response)
                        if (cached.scope == account.scope) cached = Cache(account.scope, cached.photos - account.user)
                    }
            }
        }

    private fun requireToken(account: Account) {
        check(account.token.isNotEmpty()) { "Set your ankiquest token to change your profile picture." }
    }

    /** Cache transparent corners so both widget styles display the same circular photo. */
    private fun circle(bitmap: Bitmap): Bitmap =
        createBitmap(bitmap.width, bitmap.height).apply {
            Canvas(this).drawCircle(
                width / 2f,
                height / 2f,
                minOf(width, height) / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                },
            )
        }

    internal fun decodePhoto(
        bytes: ByteArray,
        size: Int,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/png", "image/jpeg")) { "Choose a JPEG or PNG picture." }
        require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) { "This picture is too large." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Could not read this picture.")
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val matrix =
            Matrix().apply {
                // ExifInterface defines rotation after horizontal mirroring.
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val edge = minOf(oriented.width, oriented.height)
        val square = Bitmap.createBitmap(oriented, (oriented.width - edge) / 2, (oriented.height - edge) / 2, edge, edge)
        return square.scale(size, size)
    }

    private fun InputStream.boundedBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("This picture is too large.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
