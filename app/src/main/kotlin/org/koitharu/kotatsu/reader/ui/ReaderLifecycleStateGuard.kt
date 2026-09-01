package org.koitharu.kotatsu.reader.ui

/**
 * Freezes the first reliable reader position captured while an activity is moving to the
 * background. RecyclerView/ViewPager state queried later from onStop or onDestroyView may already
 * refer to recycled or preloaded pages, so it must not replace that snapshot.
 */
internal class ReaderLifecycleStateGuard {

	private var isBackgrounded = false
	private var frozenState: ReaderState? = null

	fun onBackgrounding() {
		isBackgrounded = true
		frozenState = null
	}

	fun onResumed(): Boolean {
		val wasBackgrounded = isBackgrounded
		isBackgrounded = false
		frozenState = null
		return wasBackgrounded
	}

	fun selectVisibleState(candidate: ReaderState?, fallback: ReaderState?): VisibleStateSave {
		return if (isBackgrounded) {
			// Only ReaderActivity knows which of the possibly retained fragments is current.
			VisibleStateSave(shouldSave = false, state = null)
		} else {
			VisibleStateSave(shouldSave = true, state = candidate)
		}
	}

	fun captureBackgroundState(candidate: ReaderState?, fallback: ReaderState?): VisibleStateSave {
		if (!isBackgrounded || frozenState != null) {
			return VisibleStateSave(shouldSave = false, state = null)
		}
		val snapshot = candidate ?: fallback
			?: return VisibleStateSave(shouldSave = false, state = null)
		frozenState = snapshot
		return VisibleStateSave(shouldSave = true, state = snapshot)
	}

	fun canCommitUiState(): Boolean = !isBackgrounded
}

internal data class VisibleStateSave(
	val shouldSave: Boolean,
	val state: ReaderState?,
)
