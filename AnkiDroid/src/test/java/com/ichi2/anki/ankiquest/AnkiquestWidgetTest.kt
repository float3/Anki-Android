// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.RemoteViewsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
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
    fun `tapping a player beyond the old limit opens the dashboard`() {
        val root = populatedWidget()
        val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
        val adapter = assertNotNull(list.adapter)
        measureWidget(root)
        list.setSelection(7)
        measureWidget(root)
        // RemoteViews resolves the click template through the row's real AdapterView parent.
        val row = assertNotNull(list.getChildAt(7 - list.firstVisiblePosition))
        assertEquals("Player 8", row.text(R.id.ankiquest_widget_name))

        assertTrue(list.performItemClick(row, 7, adapter.getItemId(7)))
        shadowOf(Looper.getMainLooper()).idle()

        val application = ApplicationProvider.getApplicationContext<Application>()
        val intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(ComponentName(targetContext, AnkiquestActivity::class.java), intent.component)
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
        assertEquals(ComponentName(targetContext, AnkiquestActivity::class.java), intent.component)
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
