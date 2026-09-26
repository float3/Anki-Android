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
package com.ichi2.anki.preferences

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestActivity
import com.ichi2.anki.ankiquest.AnkiquestAvatarEditor
import com.ichi2.anki.ankiquest.AnkiquestAvatars
import com.ichi2.anki.ankiquest.AnkiquestDeckAdapter
import com.ichi2.anki.ankiquest.AnkiquestDeckTree
import com.ichi2.anki.ankiquest.AnkiquestNotifier
import com.ichi2.anki.ankiquest.AnkiquestPoll
import com.ichi2.anki.ankiquest.AnkiquestUpdater
import com.ichi2.preferences.VersatileTextPreference
import com.ichi2.utils.Permissions.openAppSettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class AnkiquestSettingsFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest
    override val analyticsScreenNameConstant = "prefs.ankiquest"

    private var running = false
    private val refreshServerSettings = mutableListOf<() -> Unit>()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) AnkiquestPoll.refreshNow(requireContext())
        }

    // Deliberately memory-only: a restored picker result is discarded after process recreation.
    private var pictureAccount: AnkiquestAvatars.Account? = null
    private var pictureEditor: AlertDialog? = null
    private val profilePicture =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val account = pictureAccount
            pictureAccount = null
            if (uri == null || account == null) return@registerForActivityResult
            pictureAction {
                AnkiquestAvatars.requireCurrent(account)
                val bitmap = AnkiquestAvatars.prepare(requireContext(), uri)
                AnkiquestAvatars.requireCurrent(account)
                if (!isAdded || view == null) return@pictureAction
                pictureEditor?.dismiss()
                pictureEditor =
                    AnkiquestAvatarEditor.show(requireContext(), bitmap) { cropped ->
                        pictureAction {
                            AnkiquestAvatars.save(account, cropped)
                            AnkiquestPoll.refreshNow(requireContext())
                        }
                    }
            }
        }

    override fun initSubscreen() {
        refreshServerSettings.clear()
        requirePreference<VersatileTextPreference>(R.string.ankiquest_url_key).continuousValidator =
            VersatileTextPreference.Validator { value ->
                if (value.isNotEmpty()) value.toHttpUrl()
            }
        requirePreference<SwitchPreferenceCompat>(R.string.ankiquest_notify_rank_key).setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true) askForNotifications()
            true
        }
        requirePreference<ListPreference>(R.string.ankiquest_streak_hours_key).setOnPreferenceChangeListener { _, hours ->
            if (hours != "0") askForNotifications()
            true
        }
        bindServerToggle(
            R.string.ankiquest_nudges_key,
            read = { Ankiquest.nudgesEnabled() },
            save = { Ankiquest.setNudges(it).getBoolean("nudges") },
            notify = true,
        )
        bindServerToggle(
            R.string.ankiquest_streak_protection_key,
            read = { Ankiquest.streakProtectionEnabled() },
            save = { Ankiquest.setStreakProtection(it) },
        )
        bindDashboard(R.string.ankiquest_dashboard_key)
        bindDashboard(R.string.ankiquest_community_reminders_key, community = true)
        requirePreference<Preference>(R.string.ankiquest_avatar_key).setOnPreferenceClickListener {
            pictureAction {
                val account = checkNotNull(AnkiquestAvatars.account()) { getString(R.string.ankiquest_nudges_unavailable) }
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.ankiquest_avatar_title)
                    .setItems(
                        arrayOf(getString(R.string.ankiquest_avatar_choose), getString(R.string.ankiquest_avatar_remove)),
                    ) { _, option ->
                        if (option == 0) {
                            pictureAction {
                                AnkiquestAvatars.requireCurrent(account)
                                pictureAccount = account
                                try {
                                    profilePicture.launch("image/*")
                                } catch (e: Exception) {
                                    pictureAccount = null
                                    throw e
                                }
                            }
                        } else {
                            AlertDialog
                                .Builder(requireContext())
                                .setMessage(R.string.ankiquest_avatar_remove_confirm)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.ankiquest_avatar_remove) { _, _ ->
                                    pictureAction {
                                        AnkiquestAvatars.remove(account)
                                        AnkiquestPoll.refreshNow(requireContext())
                                    }
                                }.show()
                        }
                    }.show()
            }
            true
        }
        bindAlertSettings(R.string.ankiquest_message_alerts_key, nudge = false)
        bindAlertSettings(R.string.ankiquest_nudge_alerts_key, nudge = true)
        bindAction(R.string.ankiquest_test_key) { Ankiquest.runFromSettings(requireContext(), uploadAll = false) }
        bindAction(R.string.ankiquest_upload_all_key) { Ankiquest.runFromSettings(requireContext(), uploadAll = true) }
        bindAction(R.string.ankiquest_deck_notifications_key) {
            try {
                showDeckNotifications(Ankiquest.deckNotificationSettings())
                null
            } catch (e: Exception) {
                getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
            }
        }
        requirePreference<Preference>(R.string.ankiquest_check_updates_key).summary =
            getString(
                R.string.ankiquest_check_updates_summary,
                AnkiquestUpdater.installed() ?: getString(R.string.ankiquest_local_build),
            )
        bindAction(R.string.ankiquest_check_updates_key) { AnkiquestUpdater.checkNow(requireActivity()) }
    }

    private fun pictureAction(action: suspend () -> Unit) {
        val preference = requirePreference<Preference>(R.string.ankiquest_avatar_key)
        if (!preference.isEnabled) return
        preference.isEnabled = false
        lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    AlertDialog
                        .Builder(requireContext())
                        .setTitle(R.string.ankiquest_avatar_title)
                        .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                preference.isEnabled = true
            }
        }
    }

    private fun bindAlertSettings(
        key: Int,
        nudge: Boolean,
    ) {
        requirePreference<Preference>(key).setOnPreferenceClickListener {
            try {
                startActivity(AnkiquestNotifier.alertSettingsIntent(requireContext(), nudge))
            } catch (_: ActivityNotFoundException) {
                openAppSettingsScreen()
            }
            true
        }
    }

    private fun bindDashboard(
        key: Int,
        community: Boolean = false,
    ) {
        requirePreference<Preference>(key).setOnPreferenceClickListener {
            if (Ankiquest.dashboardUrl() == null) {
                AlertDialog
                    .Builder(requireContext())
                    .setMessage(R.string.ankiquest_check_unconfigured)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                startActivity(
                    Intent(requireContext(), AnkiquestActivity::class.java)
                        .putExtra(AnkiquestActivity.COMMUNITY_REMINDERS, community),
                )
            }
            true
        }
    }

    /** Server switches are refreshed for the current account and change only after the server confirms. */
    private fun bindServerToggle(
        key: Int,
        read: suspend () -> Boolean,
        save: suspend (Boolean) -> Boolean,
        notify: Boolean = false,
    ) {
        val preference = requirePreference<SwitchPreferenceCompat>(key)
        val summary = preference.summary
        var generation = 0
        var saving = false
        var refreshPending = false
        preference.isPersistent = false
        preference.isEnabled = false
        val refresh = refresh@{
            val request = ++generation
            preference.isEnabled = false
            preference.summary = getString(R.string.ankiquest_check_running)
            // Returning to settings during a write must read the state after that write completes.
            if (saving) {
                refreshPending = true
                return@refresh
            }
            val account = Ankiquest.webSession()
            lifecycleScope.launch {
                try {
                    val enabled = read()
                    if (request != generation || account != Ankiquest.webSession() || !isAdded) return@launch
                    preference.isChecked = enabled
                    preference.isEnabled = true
                    preference.summary = summary
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request != generation || account != Ankiquest.webSession() || !isAdded) return@launch
                    preference.summary = getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }
        refreshServerSettings.add { refresh() }
        preference.setOnPreferenceChangeListener { _, value ->
            val wanted = value == true
            val request = ++generation
            val account = Ankiquest.webSession()
            preference.isEnabled = false
            saving = true
            lifecycleScope.launch {
                try {
                    if (account != Ankiquest.webSession()) return@launch
                    val enabled = save(wanted)
                    if (request != generation || account != Ankiquest.webSession() || !isAdded) return@launch
                    preference.isChecked = enabled
                    preference.isEnabled = true
                    if (enabled && notify) askForNotifications()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request != generation || account != Ankiquest.webSession() || !isAdded) return@launch
                    AlertDialog
                        .Builder(requireContext())
                        .setTitle(preference.title)
                        .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    refresh()
                } finally {
                    saving = false
                    if (refreshPending && isAdded) {
                        refreshPending = false
                        refresh()
                    }
                }
            }
            false
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key in setOf(Ankiquest.URL_KEY, Ankiquest.USER_KEY, Ankiquest.TOKEN_KEY)) {
            pictureAccount = null
            pictureEditor?.dismiss()
            pictureEditor = null
            refreshServerSettings.forEach { it() }
        }
    }

    private fun bindAction(
        key: Int,
        action: suspend () -> String?,
    ) {
        val preference = requirePreference<Preference>(key)
        val idleSummary = preference.summary
        preference.setOnPreferenceClickListener {
            if (running) return@setOnPreferenceClickListener true
            running = true
            preference.summary = getString(R.string.ankiquest_check_running)
            val context = requireContext()
            lifecycleScope.launch {
                val message =
                    try {
                        action()
                    } finally {
                        running = false
                        preference.summary = idleSummary
                    }
                if (message == null) return@launch
                AlertDialog
                    .Builder(context)
                    .setTitle(preference.title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            true
        }
    }

    private fun showDeckNotifications(settings: JSONObject) {
        val context = requireContext()
        val rows = settings.getJSONArray("decks")
        if (rows.length() == 0) {
            AlertDialog
                .Builder(context)
                .setMessage(R.string.ankiquest_deck_notifications_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val tree = AnkiquestDeckTree((0 until rows.length()).map { rows.getJSONObject(it) })
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        val adapter =
            AnkiquestDeckAdapter(
                tree,
                BooleanArray(tree.size) { tree.rows[it].deck.getBoolean("enabled") },
            ) {}
        list.adapter = adapter
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_deck_notifications_title)
                .setView(list)
                .setPositiveButton(R.string.ankiquest_deck_notifications_next, null)
                .setNeutralButton(R.string.ankiquest_deck_notifications_select_all, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                adapter.setAll(adapter.anyUnchecked())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val shared = tree.rows.filterIndexed { index, _ -> adapter.checked[index] }.map { it.deck }
                val unshared = tree.rows.filterIndexed { index, _ -> !adapter.checked[index] }.map { it.deck }
                dialog.dismiss()
                chooseRecipients(settings, shared, unshared)
            }
        }
        dialog.show()
    }

    /** Asks once who should hear about every newly shared deck. */
    private fun chooseRecipients(
        settings: JSONObject,
        shared: List<JSONObject>,
        unshared: List<JSONObject>,
    ) {
        val context = requireContext()
        val ids = { decks: List<JSONObject> -> decks.map { it.getString("id") } }
        if (shared.isEmpty()) {
            saveDeckSharing(emptyList(), ids(unshared), emptyList())
            return
        }
        val rows = settings.getJSONArray("recipients")
        val people = (0 until rows.length()).map { rows.getJSONObject(it) }
        if (people.isEmpty()) {
            AlertDialog
                .Builder(context)
                .setMessage(R.string.ankiquest_deck_notifications_no_people)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val chosen =
            shared
                .flatMap { deck ->
                    deck.getJSONArray("recipients").let { list -> (0 until list.length()).map { list.getString(it) } }
                }.toSet()
        val checked = people.map { it.getString("user") in chosen }.toBooleanArray()
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_deck_notifications_people_title)
                .setMultiChoiceItems(
                    people.map { "${it.getString("display")} (${it.getString("user")})" }.toTypedArray(),
                    checked,
                ) { _, index, value -> checked[index] = value }
                .setPositiveButton(R.string.ankiquest_deck_notifications_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val recipients = people.filterIndexed { index, _ -> checked[index] }.map { it.getString("user") }
                if (recipients.isEmpty()) {
                    AlertDialog
                        .Builder(context)
                        .setMessage(R.string.ankiquest_deck_notifications_choose_people)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                saveDeckSharing(ids(shared), ids(unshared), recipients)
            }
        }
        dialog.show()
    }

    private fun saveDeckSharing(
        shared: List<String>,
        unshared: List<String>,
        recipients: List<String>,
    ) {
        val context = requireContext()
        lifecycleScope.launch {
            try {
                Ankiquest.saveDeckNotificationSettings(shared, unshared, recipients)
                AlertDialog
                    .Builder(context)
                    .setMessage(
                        resources.getQuantityString(
                            R.plurals.ankiquest_deck_notifications_saved,
                            shared.size,
                            shared.size,
                        ),
                    ).setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: Exception) {
                AlertDialog
                    .Builder(context)
                    .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val prefs = preferenceManager.sharedPreferences ?: return
        if (!prefs.getBoolean(ASKED_KEY, false)) {
            prefs.edit { putBoolean(ASKED_KEY, true) }
            askForNotifications()
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refreshServerSettings.forEach { it() }
        // Deliver pending messages promptly after returning from Android alert settings.
        AnkiquestPoll.refreshNow(requireContext())
    }

    override fun onDestroyView() {
        pictureEditor?.dismiss()
        pictureEditor = null
        super.onDestroyView()
    }

    companion object {
        private const val ASKED_KEY = "ankiquestAskedNotifications"
    }
}
