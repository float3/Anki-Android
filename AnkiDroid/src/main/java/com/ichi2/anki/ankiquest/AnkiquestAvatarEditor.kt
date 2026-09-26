// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.R
import kotlin.math.roundToInt

object AnkiquestAvatarEditor {
    fun show(
        context: Context,
        bitmap: Bitmap,
        save: (Bitmap) -> Unit,
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        val preview = AnkiquestAvatarCropView(context, bitmap)
        val slider =
            SeekBar(context).apply {
                max = 300
                contentDescription = context.getString(R.string.ankiquest_avatar_zoom)
            }
        preview.onZoomChanged = { slider.progress = ((it - 1) * 100).roundToInt() }
        slider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) preview.setZoom(1 + progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (12 * density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(TextView(context).apply { setText(R.string.ankiquest_avatar_crop_hint) })
                addView(preview, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply { setText(R.string.ankiquest_avatar_zoom) })
                addView(slider)
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val step = 24 * density
                        for ((label, description, move) in listOf(
                            Triple("←", R.string.ankiquest_avatar_move_left) { preview.pan(-step, 0f) },
                            Triple("↑", R.string.ankiquest_avatar_move_up) { preview.pan(0f, -step) },
                            Triple("↓", R.string.ankiquest_avatar_move_down) { preview.pan(0f, step) },
                            Triple("→", R.string.ankiquest_avatar_move_right) { preview.pan(step, 0f) },
                        )) {
                            addView(
                                Button(context).apply {
                                    text = label
                                    contentDescription = context.getString(description)
                                    minimumWidth = 0
                                    setPadding(0, paddingTop, 0, paddingBottom)
                                    setOnClickListener { move() }
                                },
                                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                            )
                        }
                        addView(
                            Button(context).apply {
                                setText(R.string.ankiquest_avatar_reset)
                                minimumWidth = 0
                                setOnClickListener { preview.reset() }
                            },
                            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f),
                        )
                    },
                )
            }
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_avatar_title)
                .setView(ScrollView(context).apply { addView(content) })
                .setPositiveButton(R.string.ankiquest_deck_notifications_save) { _, _ -> save(preview.crop()) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnDismissListener { preview.clear() }
        dialog.show()
        dialog.window?.setLayout(
            minOf((560 * density).toInt(), context.resources.displayMetrics.widthPixels - (32 * density).toInt()),
            LayoutParams.WRAP_CONTENT,
        )
        return dialog
    }
}
