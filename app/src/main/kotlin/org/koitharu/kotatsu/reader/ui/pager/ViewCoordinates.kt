package org.koitharu.kotatsu.reader.ui.pager

import android.graphics.Matrix
import android.graphics.PointF
import android.view.View

/** Includes ancestor zoom, item translation and window placement; works on API 26+. */
internal fun View.matrixToScreen(): Matrix {
    val root = rootView
    val location = IntArray(2)
    root.getLocationOnScreen(location)
    val result = Matrix().apply { setTranslate(location[0].toFloat(), location[1].toFloat()) }
    fun append(view: View) {
        if (view === root) return
        val parent = view.parent as? View ?: return
        append(parent)
        result.preTranslate((view.left - parent.scrollX).toFloat(), (view.top - parent.scrollY).toFloat())
        result.preConcat(view.matrix)
    }
    append(this)
    return result
}

internal fun View.screenToLocal(x: Float, y: Float): PointF? {
    val inverse = Matrix()
    if (!matrixToScreen().invert(inverse)) return null
    val point = floatArrayOf(x, y)
    inverse.mapPoints(point)
    return PointF(point[0], point[1])
}
