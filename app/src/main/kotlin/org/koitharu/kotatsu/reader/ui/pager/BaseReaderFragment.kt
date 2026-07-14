package org.koitharu.kotatsu.reader.ui.pager

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import androidx.viewbinding.ViewBinding
import org.koitharu.kotatsu.core.prefs.ReaderAnimation
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

internal fun shouldReanchorAfterPageListUpdate(oldPosition: Int, newPosition: Int): Boolean =
	oldPosition >= 0 && newPosition >= 0 && oldPosition != newPosition

abstract class BaseReaderFragment<B : ViewBinding> : BaseFragment<B>(), ZoomControl.ZoomControlListener {

	protected val viewModel by activityViewModels<ReaderViewModel>()

	protected var readerAdapter: BaseReaderAdapter<*>? = null
		private set

	override fun onViewBindingCreated(binding: B, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		readerAdapter = onCreateAdapter()

		viewModel.content.observe(viewLifecycleOwner) {
			val currentState = viewModel.getCurrentState()
			val currentOldPosition = currentState?.let { state ->
				readerAdapter?.indexOf(state.chapterId, state.page)
			} ?: -1
			val currentNewPosition = currentState?.let { state ->
				it.pages.indexOfFirst { page ->
					page.chapterId == state.chapterId && page.index == state.page
				}
			} ?: -1
			val pendingState = when {
				// Appending a preloaded next chapter does not move the current page, and RecyclerView's
				// diff keeps its visual position. Re-anchoring that unchanged page interrupts an active
				// webtoon scroll and can make holders reload. Only restore when a prepend/front trim
				// actually changed the current page's adapter position.
				readerAdapter?.hasItems == true -> if (
					it.state == null &&
					it.pages.isNotEmpty() &&
					shouldReanchorAfterPageListUpdate(currentOldPosition, currentNewPosition)
				) {
					currentState
				} else {
					null
				}
				it.state == null
					&& it.pages.isNotEmpty() -> currentState
				it.state != currentState
					&& currentState != null
					&& it.pages.any { page -> page.chapterId == currentState.chapterId } -> currentState
				else -> it.state
			}
			onPagesChanged(it.pages, pendingState)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onPause() {
		super.onPause()
		val state = getCurrentState()
		viewModel.saveCurrentState(state)
	}

	override fun onDestroyView() {
		viewModel.saveCurrentState(getCurrentState())
		readerAdapter = null
		super.onDestroyView()
	}

	protected fun requireAdapter() = checkNotNull(readerAdapter) {
		"Adapter was not created or already destroyed"
	}

	protected fun isAnimationEnabled(): Boolean {
		return context?.isAnimationsEnabled == true && viewModel.pageAnimation.value != ReaderAnimation.NONE
	}

	abstract fun switchPageBy(delta: Int)

	abstract fun switchPageTo(position: Int, smooth: Boolean)

	open fun scrollBy(delta: Int, smooth: Boolean): Boolean = false

	abstract fun getCurrentState(): ReaderState?

	protected abstract fun onCreateAdapter(): BaseReaderAdapter<*>

	protected abstract suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?)
}
