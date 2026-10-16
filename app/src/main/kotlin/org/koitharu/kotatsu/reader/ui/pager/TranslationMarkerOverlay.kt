package org.koitharu.kotatsu.reader.ui.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.reader.translate.TranslationOverflow

/** Small numbered markers for translations that could not safely fit inside their source area. */
class TranslationMarkerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var image: SubsamplingScaleImageView? = null
    private var items: List<TranslationOverflow> = emptyList()
    private var onSelected: ((Int) -> Unit)? = null
    private var pressedIndex: Int? = null
    private val point = PointF()
    private val mapping = Matrix()
    private val xy = FloatArray(2)
    private val origin = PointF()
    private val previous = FloatArray(9) { Float.NaN }
    private val values = FloatArray(9)
    private var previousScale = Float.NaN
    private var previousOrigin = PointF(Float.NaN, Float.NaN)
    private var previousOrientation = -1
    private var downEvent: MotionEvent? = null
    private var forwarding = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val view = image
        if (view != null && items.isNotEmpty() && view.isReady && updateMapping(view)) {
            mapping.getValues(values)
            val currentOrigin = view.sourceToViewCoord(0f, 0f, origin)
            if (!values.contentEquals(previous) || previousScale != view.scale || previousOrigin != currentOrigin ||
                previousOrientation != view.orientation) {
                values.copyInto(previous)
                previousScale = view.scale
                previousOrigin.set(currentOrigin ?: PointF(Float.NaN, Float.NaN))
                previousOrientation = view.orientation
                invalidate()
            }
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        downEvent?.recycle()
        downEvent = null
        pressedIndex = null
        forwarding = false
        super.onDetachedFromWindow()
    }

    private fun updateMapping(view: SubsamplingScaleImageView): Boolean {
        val inverse = Matrix()
        if (!matrixToScreen().invert(inverse)) return false
        mapping.setConcat(inverse, view.matrixToScreen())
        return true
    }

    private fun markerPoint(view: SubsamplingScaleImageView, item: TranslationOverflow): PointF? {
        val x = item.block.rect.centerX()
        val y = item.block.rect.centerY()
        val displayX = when (view.orientation) { 90 -> 1f - y; 180 -> 1f - x; 270 -> y; else -> x }
        val displayY = when (view.orientation) { 90 -> x; 180 -> 1f - y; 270 -> 1f - x; else -> y }
        val mapped = view.sourceToViewCoord(displayX * view.displaySourceWidth, displayY * view.displaySourceHeight, point) ?: return null
        xy[0] = mapped.x
        xy[1] = mapped.y
        mapping.mapPoints(xy)
        point.set(xy[0], xy[1])
        return point
    }

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff356b77.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun showMarkers(image: SubsamplingScaleImageView, items: List<TranslationOverflow>, onSelected: (Int) -> Unit) {
        pressedIndex = null
        downEvent?.recycle()
        downEvent = null
        forwarding = false
        this.image = image
        this.items = items
        this.onSelected = onSelected
        visibility = if (items.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val view = image?.takeIf { it.isReady } ?: return
        if (!updateMapping(view)) return
        val radius = 16f * resources.displayMetrics.density
        label.textSize = 13f * resources.displayMetrics.scaledDensity
        items.forEach { item ->
            val mapped = markerPoint(view, item) ?: return@forEach
            if (mapped.x !in -radius..(width + radius) || mapped.y !in -radius..(height + radius)) return@forEach
            canvas.drawCircle(mapped.x, mapped.y, radius, circle)
            canvas.drawText(item.index.toString(), mapped.x, mapped.y - (label.ascent() + label.descent()) / 2f, label)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val view = image?.takeIf { it.isReady } ?: return false
        if (!updateMapping(view)) return false
        if (forwarding) {
            forward(view, event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val radius = 24f * resources.displayMetrics.density
                pressedIndex = items.firstOrNull { item ->
                    val mapped = markerPoint(view, item) ?: return@firstOrNull false
                    kotlin.math.hypot(mapped.x - event.x, mapped.y - event.y) <= radius
                }?.index
                downEvent?.recycle()
                downEvent = if (pressedIndex != null) MotionEvent.obtain(event) else null
                return pressedIndex != null
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                val down = downEvent ?: return false
                val moved = kotlin.math.hypot(event.x - down.x, event.y - down.y) > ViewConfiguration.get(context).scaledTouchSlop
                if (moved || event.pointerCount > 1) {
                    // Replay the original down so a drag or pinch that starts on a marker remains a reader gesture.
                    forward(view, down)
                    forward(view, event)
                    forwarding = true
                    pressedIndex = null
                    down.recycle()
                    downEvent = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val selected = pressedIndex
                pressedIndex = null
                downEvent?.recycle()
                downEvent = null
                if (selected != null) {
                    performClick()
                    onSelected?.invoke(selected)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = null
                downEvent?.recycle()
                downEvent = null
                return true
            }
        }
        return pressedIndex != null
    }

    private fun forward(view: View, event: MotionEvent) {
        val transform = Matrix()
        if (!view.matrixToScreen().invert(transform)) return
        transform.preConcat(matrixToScreen())
        val copy = MotionEvent.obtain(event)
        try {
            copy.transform(transform)
            view.dispatchTouchEvent(copy)
        } finally { copy.recycle() }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
