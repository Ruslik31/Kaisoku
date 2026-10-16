package org.koitharu.kotatsu.reader.ui.pager

import android.graphics.PointF
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/** Coordinates stay normalized to the original (possibly cropped) bitmap. No pixels are copied. */
internal object PageRotation {
    fun orientation(width: Int, height: Int, preference: Int): Int =
        if (width > height && height > 0 && (preference == 90 || preference == 270)) preference else 0

    fun toDisplay(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }

    fun toOriginal(x: Float, y: Float, rotation: Int): Pair<Float, Float> =
        toDisplay(x, y, (360 - rotation) % 360)
}

internal val SubsamplingScaleImageView.displaySourceWidth: Int
    get() = if (orientation == 90 || orientation == 270) sHeight else sWidth
internal val SubsamplingScaleImageView.displaySourceHeight: Int
    get() = if (orientation == 90 || orientation == 270) sWidth else sHeight

internal fun SubsamplingScaleImageView.normalizedSourceToView(x: Float, y: Float): PointF? {
    val (displayX, displayY) = PageRotation.toDisplay(x, y, orientation)
    return sourceToViewCoord(displayX * displaySourceWidth, displayY * displaySourceHeight)
}
