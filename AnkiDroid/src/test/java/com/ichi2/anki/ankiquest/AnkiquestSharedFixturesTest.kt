// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Checks this client against `tests/fixtures/clients.json` from the ankiquest server, which the
 * desktop add-on and website share. The ankiquest_fixtures workflow keeps this copy identical.
 */
class AnkiquestSharedFixturesTest {
    private val fixtures =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/clients.json")) { "missing shared fixtures" }.readText(),
        )

    private fun cases(name: String): List<JSONObject> =
        fixtures.getJSONArray(name).let { all -> (0 until all.length()).map(all::getJSONObject) }

    private fun JSONArray.longs(): List<Long> = (0 until length()).map(::getLong)

    @Test
    fun `study days match the server`() {
        val cases = cases("study_day")
        check(cases.isNotEmpty())
        for (case in cases) {
            assertEquals(
                case.getLong("day"),
                AnkiquestDecks.day(case.getLong("now_ms"), case.getInt("offset_west_min"), case.getInt("rollover_hour")),
                case.toString(),
            )
        }
    }

    @Test
    fun `undo reconciliation matches the server`() {
        val cases = cases("reconcile")
        check(cases.isNotEmpty())
        for (case in cases) {
            val result =
                AnkiquestCompletionPolicy.reconcile(
                    case.getJSONArray("window").longs(),
                    case.getJSONArray("recent").longs().toSet(),
                    case.getLong("known"),
                    case.getLong("mark"),
                )
            assertEquals(case.getJSONArray("deleted").longs(), result.deleted, case.toString())
            assertEquals(case.getJSONArray("restored").longs(), result.restored, case.toString())
        }
    }

    @Test
    fun `review rows upload the fields the server reads`() {
        val cases = cases("review_row")
        check(cases.isNotEmpty())
        for (case in cases) {
            val row = case.getJSONArray("row")
            val review = AnkiquestCompletionPolicy.review(row.getLong(0), row.getLong(1), row.getLong(2), row.getLong(3), row.getInt(4))
            val expected = case.getJSONObject("review")
            val keys = expected.keys().asSequence().toSet()
            assertEquals(keys, review.keys().asSequence().toSet(), case.toString())
            for (key in keys) assertEquals(expected.getLong(key), review.getLong(key), "$key in $case")
        }
    }
}
