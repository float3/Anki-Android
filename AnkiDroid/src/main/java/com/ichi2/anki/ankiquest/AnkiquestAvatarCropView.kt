// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.ichi2.anki.R

/** A large circular preview; the uploaded square contains exactly the same crop. */
@SuppressLint("ViewConstructor") // Created by the editor with a decoded image, never inflated from XML.
class AnkiquestAvatarCropView(
    context: Context,
    bitmap: Bitmap,
) : View(context) {
    private var image: Bitmap? = bitmap
    private var centerX = bitmap.width / 2f
    private var centerY = bitmap.height / 2f
    var zoom = 1f
        private set
    var onZoomChanged: ((Float) -> Unit)? = null
    private var lastX = 0f
    private var lastY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outline = Path()
    private val previewMatrix = Matrix()
    private val border =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2
        }
    private val detector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }
            },
        )

    init {
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(R.string.ankiquest_avatar_crop_hint)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val desired = (320 * resources.displayMetrics.density).toInt()
        val edge = minOf(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        setMeasuredDimension(edge, edge)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        outline.reset()
        outline.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
        clamp()
    }

    private fun scale(bitmap: Bitmap): Float = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom

    private fun clamp() {
        val bitmap = image ?: return
        if (width == 0 || height == 0) return
        val scale = scale(bitmap)
        val halfX = minOf(width / (2 * scale), bitmap.width / 2f)
        val halfY = minOf(height / (2 * scale), bitmap.height / 2f)
        centerX = centerX.coerceIn(halfX, bitmap.width - halfX)
        centerY = centerY.coerceIn(halfY, bitmap.height - halfY)
    }

    fun pan(
        dx: Float,
        dy: Float,
    ) {
        val bitmap = image ?: return
        if (width == 0 || height == 0) return
        centerX -= dx / scale(bitmap)
        centerY -= dy / scale(bitmap)
        clamp()
        invalidate()
    }

    fun setZoom(
        value: Float,
        focusX: Float = width / 2f,
        focusY: Float = height / 2f,
    ) {
        val bitmap = image ?: return
        val oldScale = scale(bitmap)
        zoom = value.coerceIn(1f, 4f)
        if (oldScale > 0) {
            val nextScale = scale(bitmap)
            centerX += (focusX - width / 2f) * (1 / oldScale - 1 / nextScale)
            centerY += (focusY - height / 2f) * (1 / oldScale - 1 / nextScale)
        }
        clamp()
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    fun reset() {
        val bitmap = image ?: return
        centerX = bitmap.width / 2f
        centerY = bitmap.height / 2f
        zoom = 1f
        clamp()
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    private fun matrix(
        bitmap: Bitmap,
        outputSize: Int = width,
        transform: Matrix = Matrix(),
    ): Matrix {
        val ratio = outputSize.toFloat() / width
        val scale = scale(bitmap) * ratio
        return transform.apply {
            setScale(scale, scale)
            postTranslate(outputSize / 2f - centerX * scale, outputSize / 2f - centerY * scale)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = image ?: return
        val edge = minOf(width, height).toFloat()
        canvas.withClip(outline) {
            drawBitmap(bitmap, matrix(bitmap, width, previewMatrix), paint)
        }
        canvas.drawCircle(width / 2f, height / 2f, edge / 2f - border.strokeWidth / 2, border)
    }

    fun crop(): Bitmap {
        val bitmap = checkNotNull(image)
        check(width > 0 && height > 0)
        return createBitmap(256, 256).apply { Canvas(this).drawBitmap(bitmap, matrix(bitmap, 256), paint) }
    }

    fun clear() {
        image = null
        onZoomChanged = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!detector.isInProgress && event.pointerCount == 1) pan(event.x - lastX, event.y - lastY)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val step = 12 * resources.displayMetrics.density
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> pan(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> pan(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> pan(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> pan(step, 0f)
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> setZoom(zoom + .1f)
            KeyEvent.KEYCODE_MINUS -> setZoom(zoom - .1f)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
