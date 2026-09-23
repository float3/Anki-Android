/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.snackbar.showSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/** CookieManager is process-wide: no superseded screen may clear or install after its successor. */
internal class AnkiquestBrowserSessionBridge {
    private val mutex = Mutex()

    suspend fun prepare(
        current: () -> Boolean,
        clear: suspend () -> Boolean,
        bootstrap: suspend () -> String?,
        install: suspend (String) -> Boolean,
    ): Boolean =
        mutex.withLock {
            if (!current()) return@withLock false
            // A CookieManager mutation cannot be cancelled once enqueued. Wait for its
            // acknowledgement before releasing the lock, even when the screen closes.
            if (!withContext(NonCancellable) { clear() }) return@withLock false
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock false
            val cookie = bootstrap()
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock false
            if (cookie != null && !withContext(NonCancellable) { install(cookie) }) return@withLock false
            currentCoroutineContext().ensureActive()
            current()
        }
}

/** Shows the ankiquest dashboard: profile, quests, achievements and the leaderboard. */
class AnkiquestActivity : AnkiActivity(R.layout.activity_ankiquest) {
    private lateinit var webView: WebView
    private var session: AnkiquestWebSession? = null
    private var account = ""
    private var refreshAfterSettings = false
    private var clearHistoryAfterLoad = false
    private var pendingDashboard: String? = null
    private var browserBack: OnBackPressedCallback? = null
    private var prepareSession: Job? = null
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private var pictureSession: AnkiquestWebSession? = null
    private var pictureOrigin: Uri? = null
    private val choosePicture =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = fileResult
            val owner = pictureSession
            val origin = pictureOrigin
            fileResult = null
            pictureSession = null
            pictureOrigin = null
            val current = if (::webView.isInitialized) webView.url?.toUri() else null
            val accepted =
                owner != null && owner == session && owner == Ankiquest.webSession() &&
                    origin != null && acceptsPictureOrigin(current, origin)
            callback?.onReceiveValue(uri?.takeIf { accepted }?.let { arrayOf(it) })
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        session = Ankiquest.webSession()
        account = AnkiquestNavigation.accountFingerprint()
        val dashboard = initialUrl()
        if (dashboard == null) {
            startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
            finish()
            return
        }
        enableToolbar()
        setTitle(R.string.ankiquest_screen_title)
        applyInsets()

        val progress = findViewById<ProgressBar>(R.id.progress_bar)
        webView = findViewById(R.id.web_view)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    val current = session?.takeIf { it == Ankiquest.webSession() } ?: return false
                    if (!acceptsPictureOrigin(view.url?.toUri(), current.dashboard.toUri())) return false
                    cancelPictureResult()
                    fileResult = callback
                    pictureSession = current
                    pictureOrigin = view.url?.toUri()
                    return try {
                        choosePicture.launch("image/*")
                        true
                    } catch (_: android.content.ActivityNotFoundException) {
                        cancelPictureResult()
                        true
                    }
                }

                override fun onProgressChanged(
                    view: WebView,
                    newProgress: Int,
                ) {
                    progress.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                }
            }
        val back =
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    webView.goBack()
                }
            }
        onBackPressedDispatcher.addCallback(this, back)
        browserBack = back
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (session?.allows(request.url.toString()) == true) return false
                    openUrl(request.url)
                    return true
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    if (pendingDashboard != null) {
                        // A changed player differs only by the fragment, which otherwise retains the old document and credentials.
                        if (url == "about:blank" && view.url == url) {
                            val destination = checkNotNull(pendingDashboard)
                            pendingDashboard = null
                            loadSession(destination)
                        }
                        return
                    }
                    val current = Ankiquest.webSession()
                    if (current != session || current?.allows(view.url) != true || !current.allows(url)) return
                    if (clearHistoryAfterLoad) {
                        if (url != view.url) return
                        view.clearHistory()
                        clearHistoryAfterLoad = false
                        back.isEnabled = view.canGoBack()
                    }
                    current.script(url)?.let { view.evaluateJavascript(it, null) }
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    back.isEnabled = pendingDashboard == null && !clearHistoryAfterLoad && view.canGoBack()
                }
            }
        loadSession(dashboard, savedInstanceState)
        if (savedInstanceState == null && !ownedByCurrentAccount()) {
            findViewById<View>(R.id.content).showSnackbar(R.string.aq_home_account_route_changed)
        }
    }

    /** Re-authenticate before loading either the dashboard or a settings shortcut. */
    private fun loadSession(
        destination: String,
        savedInstanceState: Bundle? = null,
    ) {
        val expected = session ?: return
        val expectedAccount = account
        prepareSession?.cancel()
        prepareSession =
            lifecycleScope.launch {
                val ready =
                    sessionBridge.prepare(
                        current = {
                            expected == session && expected == Ankiquest.webSession() &&
                                expectedAccount == account && expectedAccount == AnkiquestNavigation.accountFingerprint()
                        },
                        clear = { setSessionCookie(expected.dashboard, "ankiquest_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax") },
                        bootstrap = {
                            try {
                                Ankiquest.dashboardSession(expected.dashboard)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // A public server or the website's sign-in screen can still be opened.
                                Timber.w(e, "ankiquest browser session failed")
                                null
                            }
                        },
                        install = { cookie -> setSessionCookie(expected.dashboard, cookie) },
                    )
                if (expected != session || expected != Ankiquest.webSession() ||
                    expectedAccount != account || expectedAccount != AnkiquestNavigation.accountFingerprint()
                ) {
                    return@launch
                }
                if (!ready) {
                    findViewById<ProgressBar>(R.id.progress_bar).visibility = View.GONE
                    webView.loadData("<p>Unable to prepare your connection. Close this screen and try again.</p>", "text/html", "UTF-8")
                    return@launch
                }
                if (savedInstanceState == null || savedInstanceState.getString(DASHBOARD_STATE) != expected.dashboard ||
                    savedInstanceState.getString(ACCOUNT_STATE) != expectedAccount ||
                    savedInstanceState.getString(DESTINATION_STATE) != destination || webView.restoreState(savedInstanceState) == null
                ) {
                    webView.loadUrl(destination)
                }
            }
    }

    private suspend fun setSessionCookie(
        url: String,
        cookie: String,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().setCookie(url, cookie) { accepted ->
                if (continuation.isActive) continuation.resume(accepted)
            }
        }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ankiquest, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.ankiquest_settings) return super.onOptionsItemSelected(item)
        refreshAfterSettings = true
        startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        val current = Ankiquest.webSession()
        if (current == null) {
            prepareSession?.cancel()
            cancelPictureResult()
            session = null
            account = ""
            pendingDashboard = null
            webView.loadUrl("about:blank")
            finish()
            return
        }
        val currentAccount = AnkiquestNavigation.accountFingerprint()
        if (current != session || currentAccount != account) {
            prepareSession?.cancel()
            cancelPictureResult()
            session = current
            account = currentAccount
            clearHistoryAfterLoad = true
            browserBack?.isEnabled = false
            pendingDashboard = checkNotNull(initialUrl())
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } else if (refreshAfterSettings) {
            webView.reload()
        }
        refreshAfterSettings = false
    }

    private fun initialUrl(): String? =
        session?.let { current -> destinationUrl(current.dashboard, route(intent, AnkiquestHomeData.account()?.notificationAccount)) }

    private fun ownedByCurrentAccount(): Boolean = owned(intent, AnkiquestHomeData.account()?.notificationAccount)

    private fun cancelPictureResult() {
        val callback = fileResult
        fileResult = null
        pictureSession = null
        pictureOrigin = null
        callback?.onReceiveValue(null)
    }

    override fun onDestroy() {
        cancelPictureResult()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(DASHBOARD_STATE, session?.dashboard)
        outState.putString(ACCOUNT_STATE, account)
        outState.putString(DESTINATION_STATE, initialUrl())
        if (::webView.isInitialized && pendingDashboard == null && !clearHistoryAfterLoad) webView.saveState(outState)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(systemBars() or displayCutout())
            findViewById<View>(R.id.toolbar_container).updatePadding(left = bars.left, top = bars.top, right = bars.right)
            findViewById<View>(R.id.content).updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    companion object {
        const val EXTRA_PATH = "ankiquest.path"
        const val EXTRA_ACCOUNT = "ankiquest.notification_account"
        const val COMMUNITY_REMINDERS = "ankiquestCommunityReminders"
        private const val ACCOUNT_STATE = "ankiquest.account"
        private const val DASHBOARD_STATE = "ankiquestDashboard"
        private const val DESTINATION_STATE = "ankiquestDestination"
        private val sessionBridge = AnkiquestBrowserSessionBridge()

        /**
         * Opens the website at [path], one of the routes [destinationUrl] accepts, or the player's
         * profile. With [account], the page is only shown while that account is still configured.
         */
        fun intent(
            context: Context,
            path: String?,
            account: String? = null,
        ): Intent =
            Intent(context, AnkiquestActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .apply { account?.let { putExtra(EXTRA_ACCOUNT, it) } }

        /** Whether [intent] may show its page to the [current] account; unscoped intents always may. */
        internal fun owned(
            intent: Intent,
            current: String?,
        ): Boolean = intent.getStringExtra(EXTRA_ACCOUNT).let { it == null || it == current }

        /**
         * The path [intent] asks for. A notification for another account opens this account's
         * activity instead of a coincident challenge id.
         */
        internal fun route(
            intent: Intent,
            current: String?,
        ): String? =
            when {
                !owned(intent, current) -> AnkiquestNavigation.ACTIVITY_PATH
                intent.getBooleanExtra(COMMUNITY_REMINDERS, false) -> "/community#reminders"
                else -> intent.getStringExtra(EXTRA_PATH)
            }

        /** Only known, same-server read surfaces can be opened by a native shortcut. */
        internal fun destinationUrl(
            dashboard: String,
            path: String?,
        ): String {
            val base = dashboard.toHttpUrlOrNull() ?: return dashboard
            val route = path.orEmpty().substringBefore('#')
            val allowed = setOf("/", "/community", "/records", "/hour", "/day", "/week", "/month", "/year", "/all")
            if (path == null || route !in allowed) {
                return base
                    .newBuilder()
                    .setQueryParameter("embed", "1")
                    .build()
                    .toString()
            }
            return base
                .newBuilder()
                .encodedPath(base.encodedPath + route.removePrefix("/"))
                .encodedFragment(if ('#' in path) path.substringAfter('#') else null)
                .setQueryParameter("embed", "1")
                .build()
                .toString()
        }

        internal fun acceptsPictureOrigin(
            current: Uri?,
            base: Uri,
        ): Boolean {
            val expected = base.toString().toHttpUrlOrNull() ?: return false
            val actual = current?.toString()?.toHttpUrlOrNull() ?: return false
            return actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port
        }
    }
}
