// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

/** Pure boundaries shared by completion uploads, snapshots and the private inbox. */
internal object AnkiquestCompletionPolicy {
    fun studyDay(
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): Long = (now - offsetWestMinutes * 60_000L - rolloverHour * 3_600_000L).floorDiv(86_400_000L)

    fun silentUpload(
        mark: Long,
        initialSyncDone: Boolean,
        fullBatch: Boolean,
        onlyTest: Boolean,
    ): Boolean = (mark == 0L && !initialSyncDone) || fullBatch || onlyTest

    fun freshNotification(
        createdAt: Long,
        now: Long,
    ): Boolean = createdAt > 0 && createdAt >= now - 86_400
}
