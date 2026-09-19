// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.NotificationManager
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestNotifierTest : RobolectricTest() {
    @Test
    fun `first poll delivers fresh completion and discards stale backlog`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(1, 90_000)).put(message(2, 30)))
        assertEquals(1, shadowOf(manager).size())
        assertEquals(2L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:server/cerro", 0))
        manager.cancelAll()

        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(2, 30)))
        assertEquals(0, shadowOf(manager).size())
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/other", JSONArray().put(message(2, 30)))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `disabled notifications do not consume a fresh inbox entry`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        shadowOf(manager).setNotificationsEnabled(false)
        val inbox = JSONArray().put(message(1, 30))
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        assertEquals(0L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:server/cerro", 0))
        shadowOf(manager).setNotificationsEnabled(true)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        assertEquals(1, shadowOf(manager).size())
    }

    private fun message(
        id: Long,
        ageSeconds: Long,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("created_at", TimeManager.time.intTimeMS() / 1000 - ageSeconds)
            .put("title", "Deck complete")
            .put("body", "Cerro has finished Spanish for today.")
}
