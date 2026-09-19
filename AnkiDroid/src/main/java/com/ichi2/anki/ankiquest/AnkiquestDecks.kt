// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject

/** Deck completion uses Anki's daily limits, including subdecks and learning later today. */
internal object AnkiquestDecks {
    private const val DAY_MS = 86_400_000L

    fun day(
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): Long = AnkiquestCompletionPolicy.studyDay(now, offsetWestMinutes, rolloverHour)

    fun snapshots(
        col: Collection,
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): JSONArray {
        val day = day(now, offsetWestMinutes, rolloverHour)
        val start = day * DAY_MS + offsetWestMinutes * 60_000L + rolloverHour * 3_600_000L
        val end = start + DAY_MS
        val names = col.decks.allNamesAndIds(includeFiltered = false)
        val tree = col.sched.deckDueTree().associateBy { it.did }
        val reviewed = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select case when c.odid != 0 then c.odid else c.did end, count(*) " +
                    "from revlog r join cards c on c.id = r.cid " +
                    "where r.id >= ? and r.id < ? and r.ease > 0 and r.type < 4 group by 1",
                start,
                end,
            ).use { cursor ->
                while (cursor.moveToNext()) reviewed[cursor.getLong(0)] = cursor.getLong(1)
            }
        // The deck picker only shows learning inside the learn-ahead window. Any learning
        // still due before rollover prevents completion, even when that window is empty.
        val learning = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select did, count(*) from cards where odid = 0 and queue in (1, 4) and due < ? group by did",
                end / 1000,
            ).use { cursor ->
                while (cursor.moveToNext()) learning[cursor.getLong(0)] = cursor.getLong(1)
            }
        val filtered = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select odid, count(*) from cards where odid != 0 and " +
                    "(queue = 0 or (queue in (1, 4) and due < ?) " +
                    "or (queue in (2, 3) and due <= ?)) group by odid",
                end / 1000,
                col.sched.today,
            ).use { cursor ->
                while (cursor.moveToNext()) filtered[cursor.getLong(0)] = cursor.getLong(1)
            }
        val snapshots = JSONArray()
        for (deck in names) {
            val node = tree[deck.id]
            val descendants = names.filter { it.id == deck.id || it.name.startsWith("${deck.name}::") }.map { it.id }
            val intradayInTree = node?.sumOf { it.node.intradayLearning.toLong() } ?: 0L
            // Replace Anki's short learning window (which can extend past rollover) with
            // learning before the study day ends, and attribute filtered cards to home decks.
            val due =
                if (node == null) {
                    1L // The empty Default deck may be omitted from the tree; unavailable is not completed.
                } else {
                    node.newCount.toLong() + node.revCount + maxOf(0L, node.lrnCount - intradayInTree) +
                        descendants.sumOf { (learning[it] ?: 0L) + (filtered[it] ?: 0L) }
                }
            snapshots.put(
                JSONObject()
                    .put("id", deck.id.toString())
                    .put("name", deck.name)
                    .put("remaining", due)
                    .put("reviewed_today", descendants.sumOf { reviewed[it] ?: 0L })
                    .put("day", day),
            )
        }
        return snapshots
    }
}
