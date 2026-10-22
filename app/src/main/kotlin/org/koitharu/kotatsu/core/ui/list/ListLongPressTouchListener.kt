package org.koitharu.kotatsu.core.ui.list

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import kotlin.math.abs

internal fun listLongPressTimeout(requested: Int, system: Int): Int =
    if (requested in setOf(750, 1000, 1500)) maxOf(requested, system) else 0

/** Custom timing owns this one gesture; ordinary platform/accessibility listeners remain installed. */
internal class ListLongPressTouchListener(private val boundItem: () -> Any?) :
    View.OnTouchListener, View.OnAttachStateChangeListener {

    private var target: View? = null
    private var identity: Any? = null
    private var active = false
    private var moved = false
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private var slop = 0
    private val action = Runnable {
        val view = target
        if (active && !moved && view != null && view.isAttachedToWindow && view.isEnabled && identity == boundItem()) {
            held = true
            view.performLongClick()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            val requested = PreferenceManager.getDefaultSharedPreferences(view.context)
                .getString(AppSettings.KEY_LIST_LONG_PRESS_DELAY, "0")?.toIntOrNull() ?: 0
            val timeout = listLongPressTimeout(requested, ViewConfiguration.getLongPressTimeout())
            if (timeout == 0 || !view.isEnabled || event.pointerCount != 1 || event.buttonState != 0) return false
            target = view
            identity = boundItem()
            active = true
            moved = false
            held = false
            downX = event.x
            downY = event.y
            slop = ViewConfiguration.get(view.context).scaledTouchSlop
            view.isPressed = true
            view.postDelayed(action, timeout.toLong())
            return true
        }
        if (!active) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                moved = true
                view.removeCallbacks(action)
                view.isPressed = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                view.removeCallbacks(action)
                view.isPressed = false
            }
            MotionEvent.ACTION_UP -> {
                val click = !held && !moved && view.isEnabled && identity == boundItem()
                cancel()
                if (click) view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun cancel() {
        target?.run { removeCallbacks(action); isPressed = false }
        target = null
        identity = null
        active = false
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = cancel()
}
