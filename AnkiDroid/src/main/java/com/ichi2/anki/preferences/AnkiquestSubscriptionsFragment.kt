// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.content.SharedPreferences
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestPoll
import com.ichi2.anki.ankiquest.AnkiquestSubscriptions
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.HomeAccountChanged
import com.ichi2.anki.ankiquest.IncomingSubscriptions
import com.ichi2.anki.ankiquest.SubscriptionHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Incoming permissions are independent of the decks this player chooses to share. */
class AnkiquestSubscriptionsFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_subscriptions
    override val analyticsScreenNameConstant = "prefs.ankiquest.subscriptions"

    private var settings: IncomingSubscriptions? = null
    private var account: HomeAccount? = null
    private var job: Job? = null
    private var generation = 0
    private var busy = false
    private var message: String? = null
    private var canRetry = false

    override fun initSubscreen() {
        findPreference<SwitchPreferenceCompat>(ALL_KEY)!!.setOnPreferenceChangeListener { _, value ->
            save { AnkiquestSubscriptions.repository.setEnabled(it, value == true) }
            false
        }
        findPreference<Preference>(RETRY_KEY)!!.setOnPreferenceClickListener {
            refresh()
            true
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onStop() {
        generation++
        job?.cancel()
        settings = null
        account = null
        super.onStop()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key in setOf(Ankiquest.URL_KEY, Ankiquest.USER_KEY, Ankiquest.TOKEN_KEY, "username")) refresh()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean =
        // Dynamic keys identify other players; do not include them in settings analytics.
        if (preference.key?.startsWith(SENDER_PREFIX) == true) false else super.onPreferenceTreeClick(preference)

    private fun refresh() {
        settings = null
        request(saving = false) { AnkiquestSubscriptions.repository.load(it) }
    }

    private fun save(action: suspend (HomeAccount) -> IncomingSubscriptions) {
        if (busy || settings == null) return
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            refresh()
            return
        }
        request(saving = true, action)
    }

    private fun request(
        saving: Boolean,
        action: suspend (HomeAccount) -> IncomingSubscriptions,
    ) {
        val request = ++generation
        job?.cancel()
        val captured = AnkiquestHomeData.account()
        account = captured
        canRetry = false
        if (captured == null || captured.token.isEmpty()) {
            settings = null
            busy = false
            message = getString(R.string.ankiquest_subscriptions_configure)
            render()
            return
        }
        busy = true
        message = getString(if (saving) R.string.ankiquest_subscriptions_saving else R.string.ankiquest_subscriptions_loading)
        render()
        job =
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = action(captured)
                    if (!isCurrent(request, captured)) return@launch
                    settings = result
                    message = null
                    if (saving) AnkiquestPoll.refreshNow(requireContext())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isCurrent(request, captured)) return@launch
                    // A failed write may have reached the server. Reload before allowing another edit.
                    settings = null
                    canRetry = true
                    message =
                        getString(
                            when {
                                e is SubscriptionHttpException && e.code in setOf(401, 403) -> R.string.ankiquest_subscriptions_auth
                                e is SubscriptionHttpException && e.code == 404 -> R.string.ankiquest_subscriptions_unsupported
                                e is HomeAccountChanged -> R.string.ankiquest_subscriptions_configure
                                else -> R.string.ankiquest_subscriptions_error
                            },
                        )
                } finally {
                    if (isCurrent(request, captured)) {
                        busy = false
                        render()
                    }
                }
            }
    }

    private fun isCurrent(
        request: Int,
        captured: HomeAccount,
    ): Boolean =
        request == generation && view != null && account?.scope == captured.scope && AnkiquestHomeData.account()?.scope == captured.scope

    private fun render() {
        val current = settings
        findPreference<SwitchPreferenceCompat>(ALL_KEY)!!.apply {
            isChecked = current?.enabled == true
            isEnabled = current != null && !busy
            summary =
                getString(
                    if (current?.enabled ==
                        false
                    ) {
                        R.string.ankiquest_subscriptions_paused
                    } else {
                        R.string.ankiquest_subscriptions_all_summary
                    },
                )
        }
        findPreference<Preference>(STATUS_KEY)!!.apply {
            summary =
                message
                    ?: listOfNotNull(
                        getString(
                            if (current?.senders?.isEmpty() ==
                                true
                            ) {
                                R.string.ankiquest_subscriptions_empty
                            } else {
                                R.string.ankiquest_subscriptions_scope
                            },
                        ),
                        getString(R.string.ankiquest_subscriptions_legacy).takeIf { current?.legacyMutes == true },
                    ).joinToString("\n\n")
        }
        findPreference<Preference>(RETRY_KEY)!!.isVisible = canRetry && !busy
        findPreference<PreferenceCategory>(PEOPLE_KEY)!!.apply {
            removeAll()
            isVisible = !current?.senders.isNullOrEmpty()
            current?.senders?.forEach { sender ->
                addPreference(
                    SwitchPreferenceCompat(requireContext()).apply {
                        key = senderKey(sender.user)
                        title = sender.display
                        isIconSpaceReserved = false
                        isPersistent = false
                        isChecked = sender.user !in current.unsubscribedSenders
                        isEnabled = !busy
                        summary =
                            getString(
                                if (isChecked) R.string.ankiquest_subscriptions_allowed else R.string.ankiquest_subscriptions_muted,
                                sender.user,
                            )
                        setOnPreferenceChangeListener { _, value ->
                            save { AnkiquestSubscriptions.repository.setSubscribed(it, sender.user, value == true) }
                            false
                        }
                    },
                )
            }
        }
    }

    internal companion object {
        const val ALL_KEY = "ankiquestIncomingCompletions"
        const val STATUS_KEY = "ankiquestSubscriptionsStatus"
        const val RETRY_KEY = "ankiquestSubscriptionsRetry"
        const val PEOPLE_KEY = "ankiquestSubscriptionsPeople"
        private const val SENDER_PREFIX = "ankiquestSubscription:"

        fun senderKey(user: String): String = "$SENDER_PREFIX$user"
    }
}
