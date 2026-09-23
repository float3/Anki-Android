// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.os.Looper
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestPoll
import com.ichi2.anki.ankiquest.AnkiquestSubscriptions
import com.ichi2.anki.ankiquest.IncomingSender
import com.ichi2.anki.ankiquest.IncomingSubscriptions
import com.ichi2.anki.ankiquest.SubscriptionHttpException
import com.ichi2.anki.ankiquest.SubscriptionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestSubscriptionsFragmentTest : RobolectricTest() {
    private lateinit var repository: SubscriptionRepository
    private val original =
        IncomingSubscriptions(true, setOf("old"), listOf(IncomingSender("friend", "Friend"), IncomingSender("old", "Former player")))

    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, "https://quest.example")
            putString(Ankiquest.USER_KEY, "member")
            putString(Ankiquest.TOKEN_KEY, "test-token")
        }
        repository = mockk()
        mockkObject(AnkiquestSubscriptions, AnkiquestPoll)
        every { AnkiquestSubscriptions.repository } returns repository
        justRun { AnkiquestPoll.refreshNow(any()) }
        coEvery { repository.load(any()) } returns original
    }

    @After
    fun clearMocks() {
        unmockkObject(AnkiquestSubscriptions, AnkiquestPoll)
    }

    @Test
    fun `screen distinguishes subscribed people from retained unsubscribes`() {
        val fragment = open().second
        assertTrue(fragment.all().isChecked)
        assertTrue(fragment.sender("friend").isChecked)
        assertFalse(fragment.sender("old").isChecked)
        assertTrue(
            fragment
                .sender("old")
                .summary
                .toString()
                .contains("old"),
        )
        assertFalse(fragment.sender("old").isPersistent)
    }

    @Test
    fun `subscription changes remain unconfirmed until server responds`() {
        val pending = CompletableDeferred<IncomingSubscriptions>()
        coEvery { repository.setSubscribed(any(), "friend", false) } coAnswers { pending.await() }
        val fragment = open().second
        fragment.sender("friend").performClick()
        idle()
        assertTrue(fragment.sender("friend").isChecked)
        assertFalse(fragment.all().isEnabled)
        assertFalse(fragment.sender("old").isEnabled)
        pending.complete(original.copy(unsubscribedSenders = setOf("old", "friend")))
        idle()
        assertFalse(fragment.sender("friend").isChecked)
        assertTrue(fragment.sender("old").isEnabled)
        coVerify(exactly = 1) { repository.setSubscribed(any(), "friend", false) }
    }

    @Test
    fun `global pause retains individually editable choices`() {
        coEvery { repository.load(any()) } returns original.copy(enabled = false)
        val fragment = open().second
        assertFalse(fragment.all().isChecked)
        assertTrue(fragment.sender("friend").isChecked)
        assertFalse(fragment.sender("old").isChecked)
        assertTrue(fragment.sender("old").isEnabled)
        assertEquals(targetContext.getString(R.string.ankiquest_subscriptions_paused), fragment.all().summary)
    }

    @Test
    fun `failed save disables stale choices and retry reloads confirmed server state`() {
        coEvery { repository.setSubscribed(any(), "friend", false) } throws java.io.IOException("offline")
        val fragment = open().second
        fragment.sender("friend").performClick()
        idle()
        assertFalse(fragment.all().isEnabled)
        assertEquals(0, fragment.people().preferenceCount)
        assertTrue(fragment.retry().isVisible)
        coEvery { repository.load(any()) } returns original.copy(unsubscribedSenders = setOf("friend", "old"))
        fragment.retry().performClick()
        idle()
        assertFalse(fragment.sender("friend").isChecked)
        assertTrue(fragment.all().isEnabled)
        assertFalse(fragment.retry().isVisible)
    }

    @Test
    fun `account switch clears old people and ignores the previous in flight result`() {
        val pending = CompletableDeferred<IncomingSubscriptions>()
        val previous = assertNotNull(AnkiquestHomeData.account())
        coEvery { repository.load(previous) } coAnswers { pending.await() }
        val fragment = open().second
        coEvery { repository.load(match { it.user == "new-player" }) } returns
            original.copy(senders = listOf(IncomingSender("new-friend", "New friend")))
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.USER_KEY, "new-player") }
        idle()
        pending.complete(original)
        idle()
        assertNull(fragment.findPreference<Preference>(AnkiquestSubscriptionsFragment.senderKey("friend")))
        assertTrue(fragment.sender("new-friend").isChecked)
    }

    @Test
    fun `leaving and returning reloads after any in flight write instead of applying its old result`() {
        val pending = CompletableDeferred<IncomingSubscriptions>()
        coEvery { repository.setSubscribed(any(), "friend", false) } coAnswers { pending.await() }
        val (controller, fragment) = open()
        fragment.sender("friend").performClick()
        idle()
        controller.pause().stop()
        pending.complete(original.copy(unsubscribedSenders = setOf("friend", "old")))
        coEvery { repository.load(any()) } returns original.copy(enabled = false)
        controller.start().resume().visible()
        idle()
        assertFalse(fragment.all().isChecked)
        assertTrue(fragment.sender("friend").isChecked)
        coVerify(exactly = 2) { repository.load(any()) }
    }

    @Test
    fun `unsupported server shows update guidance and no editable subscriptions`() {
        coEvery { repository.load(any()) } throws SubscriptionHttpException(404)
        val fragment = open().second
        assertEquals(targetContext.getString(R.string.ankiquest_subscriptions_unsupported), fragment.status().summary)
        assertFalse(fragment.all().isEnabled)
        assertTrue(fragment.retry().isVisible)
    }

    @Test
    fun `missing account token shows setup guidance without fetching private settings`() {
        AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.TOKEN_KEY) }
        val fragment = open().second
        assertEquals(targetContext.getString(R.string.ankiquest_subscriptions_configure), fragment.status().summary)
        assertFalse(fragment.all().isEnabled)
        assertFalse(fragment.retry().isVisible)
        coVerify(exactly = 0) { repository.load(any()) }
    }

    @Test
    fun `previous mutes are called out before a save turns them into unsubscribes`() {
        val fragment = open().second
        assertFalse(
            fragment
                .status()
                .summary
                .toString()
                .contains(targetContext.getString(R.string.ankiquest_subscriptions_legacy)),
        )
        coEvery { repository.load(any()) } returns original.copy(legacyMutes = true)
        val muted = open().second
        assertEquals(
            targetContext.getString(R.string.ankiquest_subscriptions_scope) + "\n\n" +
                targetContext.getString(R.string.ankiquest_subscriptions_legacy),
            muted.status().summary.toString(),
        )
    }

    @Test
    fun `empty subscriptions keep the global switch available`() {
        coEvery { repository.load(any()) } returns IncomingSubscriptions(true, emptySet(), emptyList())
        val fragment = open().second
        assertTrue(fragment.all().isEnabled)
        assertFalse(fragment.people().isVisible)
        assertEquals(targetContext.getString(R.string.ankiquest_subscriptions_empty), fragment.status().summary)
    }

    private fun open(): Pair<ActivityController<PreferencesActivity>, AnkiquestSubscriptionsFragment> {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestSubscriptionsFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        idle()
        val fragment = (controller.get().fragment as PreferencesFragment).childFragmentManager.findFragmentById(R.id.settings_container)
        return controller to fragment as AnkiquestSubscriptionsFragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AnkiquestSubscriptionsFragment.all() = findPreference<SwitchPreferenceCompat>(AnkiquestSubscriptionsFragment.ALL_KEY)!!

    private fun AnkiquestSubscriptionsFragment.sender(user: String) =
        findPreference<SwitchPreferenceCompat>(AnkiquestSubscriptionsFragment.senderKey(user))!!

    private fun AnkiquestSubscriptionsFragment.people() = findPreference<PreferenceCategory>(AnkiquestSubscriptionsFragment.PEOPLE_KEY)!!

    private fun AnkiquestSubscriptionsFragment.retry() = findPreference<Preference>(AnkiquestSubscriptionsFragment.RETRY_KEY)!!

    private fun AnkiquestSubscriptionsFragment.status() = findPreference<Preference>(AnkiquestSubscriptionsFragment.STATUS_KEY)!!
}
