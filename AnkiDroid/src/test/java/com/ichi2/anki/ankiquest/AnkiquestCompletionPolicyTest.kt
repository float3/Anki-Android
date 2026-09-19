// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestCompletionPolicyTest {
    @Test
    fun `first review after an empty successful baseline is announced`() {
        assertTrue(AnkiquestCompletionPolicy.silentUpload(0, initialSyncDone = false, fullBatch = false, onlyTest = false))
        // The first successful upload had no reviews, so the review cursor is still zero.
        assertFalse(AnkiquestCompletionPolicy.silentUpload(0, initialSyncDone = true, fullBatch = false, onlyTest = false))
    }

    @Test
    fun `initial history stays silent through full and final batches`() {
        assertTrue(AnkiquestCompletionPolicy.silentUpload(0, initialSyncDone = false, fullBatch = true, onlyTest = false))
        assertTrue(AnkiquestCompletionPolicy.silentUpload(0, initialSyncDone = false, fullBatch = false, onlyTest = false))
    }

    @Test
    fun `a connection test never sends an announcement`() {
        assertTrue(AnkiquestCompletionPolicy.silentUpload(123, initialSyncDone = true, fullBatch = false, onlyTest = true))
    }

    @Test
    fun `a normal upload after an established cursor can announce`() {
        assertFalse(AnkiquestCompletionPolicy.silentUpload(123, initialSyncDone = false, fullBatch = false, onlyTest = false))
    }

    @Test
    fun `local rollover defines a shared absolute study day`() {
        val day = 20_000L
        val rolloverUtc = day * 86_400_000 + 2 * 3_600_000
        assertEquals(day - 1, AnkiquestCompletionPolicy.studyDay(rolloverUtc - 1, -120, 4))
        assertEquals(day, AnkiquestCompletionPolicy.studyDay(rolloverUtc, -120, 4))
        assertEquals(-1L, AnkiquestCompletionPolicy.studyDay(0, 0, 4))
    }

    @Test
    fun `only recent notifications are eligible including the first inbox poll`() {
        val now = 1_800_000_000L
        assertTrue(AnkiquestCompletionPolicy.freshNotification(now, now))
        assertTrue(AnkiquestCompletionPolicy.freshNotification(now - 30, now))
        assertFalse(AnkiquestCompletionPolicy.freshNotification(now - 86_401, now))
        // Server time may be a little ahead of the recipient's device clock.
        assertTrue(AnkiquestCompletionPolicy.freshNotification(now + 30, now))
        assertFalse(AnkiquestCompletionPolicy.freshNotification(0, now))
    }
}
