// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.QueueType
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestDecksTest : RobolectricTest() {
    @Before
    fun useNativeSchedulerClock() {
        // The native scheduler's day and cutoff use the system clock, not RobolectricTest's 2020 clock.
        TimeManager.reset()
    }

    @Test
    fun `study day changes at local rollover rather than midnight`() {
        val day = 20_000L
        val rolloverUtc = day * 86_400_000 + 2 * 3_600_000
        assertEquals(day - 1, AnkiquestDecks.day(rolloverUtc - 1, -120, 4))
        assertEquals(day, AnkiquestDecks.day(rolloverUtc, -120, 4))
        assertEquals(-1L, AnkiquestDecks.day(0, 0, 4))
    }

    @Test
    fun `last review completes its deck but unrelated deck stays due`() {
        addBasicNote()
        val other = col.decks.id("Geography")
        val otherCard = addBasicNote("Other", "Back").firstCard()
        otherCard.did = other
        col.updateCards(listOf(otherCard))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)

        assertEquals(0L, snapshot(1).getLong("remaining"))
        assertEquals(1L, snapshot(1).getLong("reviewed_today"))
        assertEquals(1L, snapshot(other).getLong("remaining"))
    }

    @Test
    fun `daily new limit can finish today with new cards left`() {
        repeat(2) { addBasicNote("Front $it", "Back") }
        val config = col.decks.configDictForDeckId(1)
        config.new.perDay = 1
        col.decks.save(config)
        assertEquals(1L, snapshot(1).getLong("remaining"))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)

        assertEquals(1, col.findCards("is:new").size)
        assertEquals(0L, snapshot(1).getLong("remaining"))
        assertEquals(1L, snapshot(1).getLong("reviewed_today"))
    }

    @Test
    fun `learning later today keeps deck and parent unfinished`() {
        val parent = col.decks.id("Spanish")
        val child = col.decks.id("Spanish::Vocabulary")
        val card = addBasicNote().firstCard()
        card.did = child
        card.queue = QueueType.Lrn
        card.type = CardType.Lrn
        card.due = (col.sched.dayCutoff - 3600).toInt()
        col.updateCards(listOf(card))

        assertEquals(1L, snapshot(child).getLong("remaining"))
        assertEquals(1L, snapshot(parent).getLong("remaining"))
    }

    @Test
    fun `learning after rollover is excluded`() {
        val card = addBasicNote().firstCard()
        card.queue = QueueType.Lrn
        card.type = CardType.Lrn
        card.due = (col.sched.dayCutoff + 60).toInt()
        col.updateCards(listOf(card))

        assertEquals(0L, snapshot(1).getLong("remaining"))
    }

    @Test
    fun `due cards in a filtered deck keep the home deck unfinished`() {
        val card = addBasicNote().firstCard()
        val filtered = col.decks.newFiltered("Practice")
        card.oDid = 1
        card.did = filtered
        col.updateCards(listOf(card))

        assertEquals(1L, snapshot(1).getLong("remaining"))
        assertTrue(snapshots().none { it.getString("id") == filtered.toString() })
    }

    @Test
    fun `empty default deck remains in catalog when tree omits it`() {
        col.decks.id("Spanish")
        assertTrue(snapshots().any { it.getString("id") == "1" })
        assertEquals(0L, snapshot(1).getLong("reviewed_today"))
    }

    private fun snapshot(id: Long): JSONObject = snapshots().single { it.getString("id") == id.toString() }

    private fun snapshots(): List<JSONObject> {
        val now = TimeManager.time.intTimeMS()
        val rows = AnkiquestDecks.snapshots(col, now, -TimeZone.getDefault().getOffset(now) / 60_000, 4)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
}
