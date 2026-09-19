/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.core.app.PendingIntentCompat
import androidx.core.widget.RemoteViewsCompat
import androidx.core.widget.RemoteViewsCompat.RemoteCollectionItems
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import java.text.NumberFormat

/** Scrollable homescreen leaderboard. Tapping the header or a player opens the dashboard. */
open class AnkiquestWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AnkiquestPoll.schedule(context)
        AnkiquestPoll.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        AnkiquestPoll.schedule(context)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_REFRESH) {
            showRefreshing(context)
            AnkiquestPoll.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_REFRESH = "com.ichi2.anki.ankiquest.WIDGET_REFRESH"
        private const val MIN_REFRESH_MS = 30 * 1000L
        private val medals = arrayOf("👑", "🥈", "🥉")

        internal class Style(
            val provider: Class<out AnkiquestWidget>,
            @LayoutRes val layout: Int,
            @LayoutRes val rowLayout: Int,
            @DrawableRes val ownRow: Int,
        )

        internal val styles =
            listOf(
                Style(
                    AnkiquestWidget::class.java,
                    R.layout.widget_ankiquest,
                    R.layout.widget_ankiquest_row,
                    R.drawable.ankiquest_widget_row_self,
                ),
                Style(
                    AnkiquestTransparentWidget::class.java,
                    R.layout.widget_ankiquest_transparent,
                    R.layout.widget_ankiquest_row_transparent,
                    R.drawable.ankiquest_widget_row_self_clear,
                ),
            )

        @Volatile
        private var requestedAt = 0L

        /** Refreshes the leaderboard soon, at most every [MIN_REFRESH_MS]. */
        fun requestUpdate(context: Context) {
            val now = TimeManager.time.intTimeMS()
            if (now - requestedAt < MIN_REFRESH_MS) return
            requestedAt = now
            AnkiquestPoll.refreshNow(context)
        }

        private fun widgetIds(
            context: Context,
            style: Style,
        ): IntArray =
            AppWidgetManager
                .getInstance(context)
                .getAppWidgetIds(ComponentName(context, style.provider))

        private fun showRefreshing(context: Context) {
            for (style in styles) {
                val ids = widgetIds(context, style)
                if (ids.isEmpty()) continue
                val views = RemoteViews(context.packageName, style.layout)
                views.setTextViewText(R.id.ankiquest_widget_updated, "…")
                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
            }
        }

        /**
         * Draws [board] into every placed widget of every style.
         *
         * @param board this week's standings, or null when ankiquest is not configured
         * @param fetchedAt when [board] was fetched from the server
         * @param offline whether the last fetch failed and [board] is a cached copy
         */
        fun render(
            context: Context,
            board: JSONArray?,
            fetchedAt: Long,
            offline: Boolean,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            for (style in styles) {
                val ids = widgetIds(context, style)
                if (ids.isEmpty()) continue
                val items = collection(context, board ?: JSONArray(), style)
                for (id in ids) {
                    val views = layout(context, board, fetchedAt, offline, style)
                    // Each placed widget needs its own adapter ID, including transparent ones.
                    RemoteViewsCompat.setRemoteAdapter(context, views, id, R.id.ankiquest_widget_list, items)
                    manager.updateAppWidget(id, views)
                }
            }
        }

        internal fun layout(
            context: Context,
            board: JSONArray?,
            fetchedAt: Long,
            offline: Boolean,
            style: Style = styles.first(),
        ): RemoteViews {
            val views = RemoteViews(context.packageName, style.layout)
            views.setOnClickPendingIntent(
                R.id.ankiquest_widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, AnkiquestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            views.setPendingIntentTemplate(
                R.id.ankiquest_widget_list,
                PendingIntentCompat.getActivity(
                    context,
                    2,
                    Intent(context, AnkiquestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                    true,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.ankiquest_widget_refresh,
                PendingIntent.getBroadcast(
                    context,
                    1,
                    Intent(context, style.provider).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            val time =
                if (fetchedAt > 0) DateUtils.formatDateTime(context, fetchedAt, DateUtils.FORMAT_SHOW_TIME) else ""
            views.setTextViewText(
                R.id.ankiquest_widget_updated,
                if (offline) context.getString(R.string.ankiquest_widget_offline_at, time) else time,
            )

            when {
                board == null -> status(views, context.getString(R.string.ankiquest_widget_unconfigured))
                board.length() == 0 && offline -> status(views, context.getString(R.string.ankiquest_widget_offline))
                board.length() == 0 -> status(views, context.getString(R.string.ankiquest_widget_empty))
                else -> {
                    views.setViewVisibility(R.id.ankiquest_widget_status, View.GONE)
                    views.setViewVisibility(R.id.ankiquest_widget_list, View.VISIBLE)
                }
            }
            return views
        }

        private fun status(
            views: RemoteViews,
            text: String,
        ) {
            views.setTextViewText(R.id.ankiquest_widget_status, text)
            views.setViewVisibility(R.id.ankiquest_widget_status, View.VISIBLE)
            views.setViewVisibility(R.id.ankiquest_widget_list, View.GONE)
        }

        internal fun collection(
            context: Context,
            board: JSONArray,
            style: Style = styles.first(),
        ): RemoteCollectionItems {
            val items = RemoteCollectionItems.Builder().setViewTypeCount(1)
            val me = Ankiquest.player()
            val numbers = NumberFormat.getIntegerInstance()
            val leaderXp = board.optJSONObject(0)?.getLong("week_xp")?.coerceAtLeast(1) ?: 1
            for (i in 0 until board.length()) {
                val entry = board.getJSONObject(i)
                val weekXp = entry.getLong("week_xp")
                val streak = entry.optInt("streak")
                val views = RemoteViews(context.packageName, style.rowLayout)
                views.setInt(
                    R.id.ankiquest_widget_row,
                    "setBackgroundResource",
                    if (entry.getString("user") == me) style.ownRow else 0,
                )
                views.setTextViewText(R.id.ankiquest_widget_rank, medals.getOrNull(i) ?: "${i + 1}")
                views.setTextViewText(R.id.ankiquest_widget_name, entry.getString("display"))
                views.setTextViewText(R.id.ankiquest_widget_streak, if (streak > 0) "🔥$streak" else "")
                views.setTextViewText(
                    R.id.ankiquest_widget_level,
                    context.getString(R.string.ankiquest_widget_level, entry.getInt("level")),
                )
                views.setProgressBar(R.id.ankiquest_widget_bar, 1000, (weekXp * 1000 / leaderXp).toInt(), false)
                views.setTextViewText(R.id.ankiquest_widget_xp, numbers.format(weekXp))
                views.setOnClickFillInIntent(R.id.ankiquest_widget_row, Intent())
                items.addItem(i.toLong(), views)
            }
            return items.build()
        }
    }
}

/** The same leaderboard without a background, drawn straight on the wallpaper. */
class AnkiquestTransparentWidget : AnkiquestWidget()
