package org.koitharu.kotatsu.reader.ui

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Observe the chapter after asynchronous adapter/layout work, without changing reader callbacks. */
internal class ReaderChapterHaptics(
    private val view: View,
    private val lifecycle: Lifecycle,
    private val enabled: () -> Boolean,
    private val currentChapter: () -> PresentedChapter?,
) : DefaultLifecycleObserver, ViewTreeObserver.OnPreDrawListener {
    private val gate = ChapterBoundaryHapticGate()
    private var observer: ViewTreeObserver? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        gate.reset()
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onPause(owner: LifecycleOwner) {
        detach()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        detach()
        lifecycle.removeObserver(this)
    }

    override fun onPreDraw(): Boolean {
        if (!enabled() || !view.isShown || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            gate.reset()
            return true
        }
        if (gate.onPresented(currentChapter(), focused = view.hasWindowFocus())) {
            // No ignore flags: the view and system haptic policy remain authoritative.
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return true
    }

    private fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        gate.reset()
    }
}
