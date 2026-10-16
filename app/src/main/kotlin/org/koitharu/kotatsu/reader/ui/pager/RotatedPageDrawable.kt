package org.koitharu.kotatsu.reader.ui.pager

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import androidx.core.graphics.withTranslation
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable

/** Presents animated drawables without making another bitmap or interrupting their frame callbacks. */
internal class RotatedPageDrawable(val source: Drawable, val rotation: Int) : Drawable(), Drawable.Callback, Animatable {
    init { source.callback = this }
    override fun getIntrinsicWidth() = source.intrinsicHeight
    override fun getIntrinsicHeight() = source.intrinsicWidth
    override fun onBoundsChange(bounds: Rect) { source.setBounds(0, 0, bounds.height(), bounds.width()) }
    override fun draw(canvas: Canvas) {
        val x = bounds.left + if (rotation == 90) bounds.width() else 0
        val y = bounds.top + if (rotation == 90) 0 else bounds.height()
        canvas.withTranslation(x.toFloat(), y.toFloat()) {
            rotate(rotation.toFloat())
            source.draw(this)
        }
    }
    override fun setAlpha(alpha: Int) { source.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { source.colorFilter = colorFilter }
    @Suppress("DEPRECATION")
    override fun getOpacity() = source.opacity
    override fun isStateful() = source.isStateful
    override fun onStateChange(state: IntArray) = source.setState(state)
    override fun onLevelChange(level: Int) = source.setLevel(level)
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean =
        super.setVisible(visible, restart) or source.setVisible(visible, restart)
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    override fun start() { (source as? Animatable)?.start() }
    override fun stop() { (source as? Animatable)?.stop() }
    override fun isRunning() = (source as? Animatable)?.isRunning == true
}
