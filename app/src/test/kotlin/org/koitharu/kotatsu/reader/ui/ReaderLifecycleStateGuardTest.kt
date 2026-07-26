package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLifecycleStateGuardTest {

	@Test
	fun laterLifecycleSavesCannotReplacePauseSnapshot() {
		val guard = ReaderLifecycleStateGuard()
		val pauseState = ReaderState(chapterId = 30, page = 8, scroll = 4200)
		val recycledStopState = ReaderState(chapterId = 20, page = 2, scroll = 0)

		guard.onBackgrounding()

		val retainedFragmentSave = guard.selectVisibleState(recycledStopState, fallback = pauseState)
		val pauseSave = guard.captureBackgroundState(pauseState, fallback = null)
		val stopSave = guard.selectVisibleState(recycledStopState, fallback = pauseState)

		assertFalse(retainedFragmentSave.shouldSave)
		assertTrue(pauseSave.shouldSave)
		assertEquals(pauseState, pauseSave.state)
		assertFalse(stopSave.shouldSave)
	}

	@Test
	fun exactBottomSnapshotRemainsFrozenAtOneHundredPercentScroll() {
		val guard = ReaderLifecycleStateGuard()
		val bottom = ReaderState(chapterId = 99, page = 14, scroll = 10000)

		guard.onBackgrounding()

		assertEquals(bottom, guard.captureBackgroundState(bottom, fallback = null).state)
		assertFalse(
			guard.selectVisibleState(
				ReaderState(chapterId = 98, page = 3, scroll = 0),
				fallback = bottom,
			).shouldSave,
		)
	}

	@Test
	fun backgroundCallbacksAreBlockedUntilResume() {
		val guard = ReaderLifecycleStateGuard()

		guard.onBackgrounding()
		assertFalse(guard.canCommitUiState())

		guard.onResumed()
		assertTrue(guard.canCommitUiState())
		assertTrue(
			guard.selectVisibleState(
				ReaderState(chapterId = 40, page = 1, scroll = 0),
				fallback = null,
			).shouldSave,
		)
	}
}
