// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.widget.RemoteViewsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestWidgetTest : RobolectricTest() {
    @Test
    fun `all eight players retain their ranks and display names`() {
        val items = AnkiquestWidget.collection(targetContext, leaderboard())
        assertEquals(8, items.itemCount)

        for (position in listOf(3, 7)) {
            val row = items.getItemView(position).apply(targetContext, FrameLayout(targetContext))
            assertEquals("${position + 1}", row.text(R.id.ankiquest_widget_rank))
            assertEquals("Player ${position + 1}", row.text(R.id.ankiquest_widget_name))
        }
        val firstRow = items.getItemView(0).apply(targetContext, FrameLayout(targetContext))
        assertEquals("👑", firstRow.text(R.id.ankiquest_widget_rank))
    }

    @Test
    fun `compact widget can scroll to the eighth player`() {
        val root = populatedWidget()
        val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
        measureWidget(root)

        assertTrue(list.height > 0)
        assertTrue(list.bottom <= root.height - root.paddingBottom)
        assertTrue(list.canScrollVertically(1), "A compact widget must allow reaching people below the first visible rows")

        list.setSelection(7)
        measureWidget(root)

        assertEquals(7, list.lastVisiblePosition)
        val lastRow = list.getChildAt(list.childCount - 1)
        assertEquals("Player 8", lastRow.text(R.id.ankiquest_widget_name))
        assertTrue(root.findViewById<View>(R.id.ankiquest_widget_refresh).isShown)
    }

    @Test
    fun `each period ranks the leaderboard by its own xp`() {
        val board =
            JSONArray(
                listOf(
                    player("slow", week = 10, day = 99),
                    player("fast", week = 500, day = 1),
                ),
            )
        assertEquals(listOf("fast", "slow"), AnkiquestWidget.forPeriod(board, "week").names())
        assertEquals(listOf("slow", "fast"), AnkiquestWidget.forPeriod(board, "day").names())
        assertEquals(99, AnkiquestWidget.forPeriod(board, "day").getJSONObject(0).getLong("xp"))
    }

    @Test
    fun `selected period determines rendered xp and progress in both widget styles`() {
        val board =
            JSONArray(
                listOf(
                    player("slow", week = 10, day = 99),
                    player("fast", week = 500, day = 1),
                ),
            )
        for (style in AnkiquestWidget.styles) {
            val items = AnkiquestWidget.collection(targetContext, AnkiquestWidget.forPeriod(board, "day"), style)
            val leader = items.getItemView(0).apply(targetContext, FrameLayout(targetContext))
            val follower = items.getItemView(1).apply(targetContext, FrameLayout(targetContext))

            assertEquals("slow", leader.text(R.id.ankiquest_widget_name))
            assertEquals("99", leader.text(R.id.ankiquest_widget_xp))
            assertEquals(1000, leader.findViewById<ProgressBar>(R.id.ankiquest_widget_bar).progress)
            assertEquals("fast", follower.text(R.id.ankiquest_widget_name))
            assertEquals("1", follower.text(R.id.ankiquest_widget_xp))
            assertEquals(10, follower.findViewById<ProgressBar>(R.id.ankiquest_widget_bar).progress)
        }
    }

    @Test
    fun `older leaderboard data still renders weekly xp and progress`() {
        val items = AnkiquestWidget.collection(targetContext, leaderboard())
        val row = items.getItemView(3).apply(targetContext, FrameLayout(targetContext))

        assertEquals("500", row.text(R.id.ankiquest_widget_xp))
        assertEquals(625, row.findViewById<ProgressBar>(R.id.ankiquest_widget_bar).progress)
    }

    private fun player(
        user: String,
        week: Long,
        day: Long,
    ): JSONObject =
        JSONObject()
            .put("user", user)
            .put("display", user)
            .put("level", 1)
            .put("streak", 0)
            .put("xp_total", week)
            .put("week_xp", week)
            .put("periods", JSONObject().put("week", week).put("day", day))

    private fun JSONArray.names(): List<String> = (0 until length()).map { getJSONObject(it).getString("user") }

    @Test
    fun `empty leaderboard produces a valid empty collection`() {
        val items = AnkiquestWidget.collection(targetContext, JSONArray())
        assertEquals(0, items.itemCount)
        assertEquals(1, items.viewTypeCount)
    }

    @Test
    fun `empty and unconfigured updates hide the previous leaderboard and recover`() {
        val board = leaderboard()
        val root = populatedWidget(board)
        val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
        val status = root.findViewById<TextView>(R.id.ankiquest_widget_status)
        val states =
            listOf(
                Triple(JSONArray(), false, R.string.ankiquest_widget_empty),
                Triple(null, false, R.string.ankiquest_widget_unconfigured),
                Triple(JSONArray(), true, R.string.ankiquest_widget_offline),
            )

        for ((nextBoard, offline, message) in states) {
            AnkiquestWidget.layout(targetContext, nextBoard, 0, offline).reapply(targetContext, root)
            assertEquals(View.GONE, list.visibility)
            assertEquals(View.VISIBLE, status.visibility)
            assertEquals(targetContext.getString(message), status.text.toString())

            AnkiquestWidget.layout(targetContext, board, 0, false).reapply(targetContext, root)
            assertEquals(View.VISIBLE, list.visibility)
            assertEquals(View.GONE, status.visibility)
        }
    }

    @Test
    fun `configured widget header opens decks even when Today is preferred`() {
        configureTodayLaunch()
        for (transparent in listOf(false, true)) {
            val root = populatedWidget(transparent = transparent)
            assertTrue(root.performClick())
            assertNextLaunchOpensDecks()
        }
    }

    @Test
    fun `configured widget player beyond the old limit opens decks even when Today is preferred`() {
        configureTodayLaunch()
        for (transparent in listOf(false, true)) {
            val root = populatedWidget(transparent = transparent)
            val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
            val adapter = assertNotNull(list.adapter)
            measureWidget(root)
            list.setSelection(7)
            measureWidget(root)
            // RemoteViews resolves the click template through the row's real AdapterView parent.
            val row = assertNotNull(list.getChildAt(7 - list.firstVisiblePosition))
            assertEquals("Player 8", row.text(R.id.ankiquest_widget_name))

            assertTrue(list.performItemClick(row, 7, adapter.getItemId(7)))
            assertNextLaunchOpensDecks()
        }
    }

    @Test
    fun `transparent widget preserves its appearance while scrolling and opening a player`() {
        val root = populatedWidget(transparent = true)
        val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
        assertNull(root.background)
        measureWidget(root)
        assertTrue(list.canScrollVertically(1))
        list.setSelection(7)
        measureWidget(root)

        val row = assertNotNull(list.getChildAt(7 - list.firstVisiblePosition))
        val name = row.findViewById<TextView>(R.id.ankiquest_widget_name)
        assertEquals("Player 8", name.text.toString())
        assertEquals(targetContext.getColor(R.color.ankiquest_widget_clear_text), name.currentTextColor)
        assertTrue(name.shadowRadius > 0)
        assertTrue(root.findViewById<View>(R.id.ankiquest_widget_refresh).isShown)
        assertTrue(list.performItemClick(row, 7, assertNotNull(list.adapter).getItemId(7)))
        shadowOf(Looper.getMainLooper()).idle()

        val application = ApplicationProvider.getApplicationContext<Application>()
        val intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(ComponentName(targetContext, DeckPicker::class.java), intent.component)
    }

    private fun populatedWidget(
        board: JSONArray = leaderboard(),
        transparent: Boolean = false,
    ): View {
        val style = AnkiquestWidget.styles[if (transparent) 1 else 0]
        val views = AnkiquestWidget.layout(targetContext, board, 0, false, style)
        RemoteViewsCompat.setRemoteAdapter(
            targetContext,
            views,
            1,
            R.id.ankiquest_widget_list,
            AnkiquestWidget.collection(targetContext, board, style),
        )
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        val host = AppWidgetHostView(activity)
        val root = views.apply(activity, host)
        host.addView(root)
        activity.setContentView(host)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return root
    }

    private fun configureTodayLaunch() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, "https://anki.example.test")
            putString(Ankiquest.USER_KEY, "member")
            putString(Ankiquest.TOKEN_KEY, "member-token")
            putBoolean(AnkiquestNavigation.OPEN_TODAY_KEY, true)
        }
        Ankiquest.resumeUploadAt = TimeManager.time.intTimeMS()
        assertTrue(AnkiquestNavigation.enabled())
    }

    private fun assertNextLaunchOpensDecks() {
        shadowOf(Looper.getMainLooper()).idle()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(ComponentName(targetContext, DeckPicker::class.java), intent.component)
        assertTrue(intent.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertFalse(AnkiquestNavigation.opensToday(intent))
        val deckLaunchFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(deckLaunchFlags, intent.flags and deckLaunchFlags)
        assertFalse(intent.hasExtra(AnkiquestActivity.EXTRA_PATH))
    }

    private fun measureWidget(root: View) {
        val density = targetContext.resources.displayMetrics.density
        val width = (300 * density).toInt()
        val height = (150 * density).toInt()
        root.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun leaderboard(): JSONArray =
        JSONArray().apply {
            repeat(8) { position ->
                put(
                    JSONObject()
                        .put("user", "player${position + 1}")
                        .put("display", "Player ${position + 1}")
                        .put("week_xp", 800 - position * 100)
                        .put("level", position + 1)
                        .put("streak", position),
                )
            }
        }
}
