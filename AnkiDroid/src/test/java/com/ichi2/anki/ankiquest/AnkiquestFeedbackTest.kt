// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnkiquestFeedbackTest {
    private fun feedback(
        headlines: List<String>,
        status: String?,
    ): JSONObject = JSONObject().put("headlines", JSONArray(headlines)).put("status", status ?: JSONObject.NULL)

    @Test
    fun `headlines are notable and carry the status line`() {
        assertEquals(
            "📣 Spanish — told 1 friend\nLevel 5!\n+12 XP  ·  Lv 5  0/120" to true,
            Ankiquest.feedback(feedback(listOf("📣 Spanish — told 1 friend", "Level 5!"), "+12 XP  ·  Lv 5  0/120"), false),
        )
        assertEquals("Quest complete: Warm up" to true, Ankiquest.feedback(feedback(listOf("Quest complete: Warm up"), null), false))
    }

    @Test
    fun `a bare status is shown only while answering`() {
        val status = feedback(emptyList(), "−5 XP  ·  combo 0  ·  Lv 3  40/100")
        assertEquals("−5 XP  ·  combo 0  ·  Lv 3  40/100" to false, Ankiquest.feedback(status, true))
        assertNull(Ankiquest.feedback(status, false))
    }

    @Test
    fun `silent uploads and older servers show nothing`() {
        assertNull(Ankiquest.feedback(feedback(emptyList(), null), true))
        assertNull(Ankiquest.feedback(JSONObject(), true))
        assertNull(Ankiquest.feedback(null, true))
    }
}
