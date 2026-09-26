// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiquestAvatarCropViewTest : RobolectricTest() {
    private fun stripes(vertical: Boolean): Bitmap =
        createBitmap(if (vertical) 320 else 960, if (vertical) 960 else 320).apply {
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
            for (y in 0 until height) for (x in 0 until width) this[x, y] = colors[(if (vertical) y else x) / 320]
        }

    @Test
    fun `dragging reaches the top and bottom of a portrait and cannot expose blank edges`() {
        val view = AnkiquestAvatarCropView(targetContext, stripes(true))
        view.layout(0, 0, 320, 320)
        assertEquals(Color.GREEN, view.crop()[128, 128])
        for ((action, y) in listOf(MotionEvent.ACTION_DOWN to 160f, MotionEvent.ACTION_MOVE to 480f, MotionEvent.ACTION_UP to 480f)) {
            MotionEvent.obtain(0, 100, action, 160f, y, 0).let { event ->
                view.onTouchEvent(event)
                event.recycle()
            }
        }
        assertEquals(Color.RED, view.crop()[128, 128])
        view.pan(0f, -10000f)
        val bottom = view.crop()
        assertEquals(Color.BLUE, bottom[128, 128])
        assertEquals(255, Color.alpha(bottom[0, 0]))
        assertEquals(255, Color.alpha(bottom[255, 255]))
        view.reset()
        assertEquals(Color.GREEN, view.crop()[128, 128])
    }

    @Test
    fun `landscape panning reaches both ends and zoom exports the chosen pixels`() {
        val view = AnkiquestAvatarCropView(targetContext, stripes(false))
        view.layout(0, 0, 320, 320)
        view.pan(10000f, 0f)
        assertEquals(Color.RED, view.crop()[128, 128])
        view.pan(-10000f, 0f)
        assertEquals(Color.BLUE, view.crop()[128, 128])
        val source =
            createBitmap(320, 320).apply {
                eraseColor(Color.RED)
                for (y in 100 until 220) for (x in 100 until 220) this[x, y] = Color.GREEN
            }
        val zoomed = AnkiquestAvatarCropView(targetContext, source)
        zoomed.layout(0, 0, 320, 320)
        assertEquals(Color.RED, zoomed.crop()[8, 8])
        zoomed.setZoom(10f)
        assertEquals(4f, zoomed.zoom)
        assertEquals(Color.GREEN, zoomed.crop()[8, 8])
        zoomed.reset()
        assertEquals(1f, zoomed.zoom)
        assertEquals(Color.RED, zoomed.crop()[8, 8])
    }

    @Test
    fun `circular preview matches the saved center and closing releases its image`() {
        val view = AnkiquestAvatarCropView(targetContext, stripes(true))
        view.layout(0, 0, 320, 320)
        val preview = createBitmap(320, 320)
        view.draw(Canvas(preview))
        assertEquals(0, Color.alpha(preview[0, 0]))
        assertEquals(view.crop()[128, 128], preview[160, 160])
        view.clear()
        assertFailsWith<IllegalStateException> { view.crop() }
        MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 160f, 160f, 0).let { event ->
            assertFalse(view.onTouchEvent(event))
            event.recycle()
        }
    }
}
