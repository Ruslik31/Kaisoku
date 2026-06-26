package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterSwitchCursorTest {

	private val chapters = listOf(10L, 20L, 30L, 40L, 50L)

	@Test
	fun singlePressAdvancesByOne() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(20L)
		assertEquals(30L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
	}

	@Test
	fun singlePressGoesBack() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(30L)
		assertEquals(20L, cursor.resolveRelative(chapters, liveChapterId = 30L, delta = -1))
	}

	/**
	 * Regression: a burst of "next" presses must chain (20 -> 30 -> 40) instead of each press
	 * re-deriving from the same un-committed reading state and advancing only once.
	 */
	@Test
	fun rapidNextChainsAcrossPresses() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(20L)
		// reading state is still 20 for every press because no load has committed yet
		assertEquals(30L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
		assertEquals(40L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
		assertEquals(50L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
	}

	@Test
	fun rapidPrevChainsAcrossPresses() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(40L)
		assertEquals(30L, cursor.resolveRelative(chapters, liveChapterId = 40L, delta = -1))
		assertEquals(20L, cursor.resolveRelative(chapters, liveChapterId = 40L, delta = -1))
	}

	/**
	 * Regression: while a chapter load/re-anchor is in flight the live reading state can briefly
	 * report an adjacent preloaded chapter. The cursor must ignore that and stay anchored to the
	 * chapter the user actually navigated to.
	 */
	@Test
	fun corruptedLiveStateIsIgnoredWhileAnchored() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(30L)
		// live state momentarily points at the prepended previous chapter (20)
		assertEquals(40L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
		// and pressing again still moves forward, not back to the start of 30
		assertEquals(50L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
	}

	@Test
	fun settleReanchorsToScrolledChapter() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(10L)
		// user scrolls naturally into chapter 40; scrolling settles there
		cursor.settle(40L)
		assertEquals(50L, cursor.resolveRelative(chapters, liveChapterId = 40L, delta = 1))
	}

	@Test
	fun nextPastLastChapterDoesNotMove() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(50L)
		assertNull(cursor.resolveRelative(chapters, liveChapterId = 50L, delta = 1))
		// anchor is unchanged, so a following prev still works from 50
		assertEquals(40L, cursor.resolveRelative(chapters, liveChapterId = 50L, delta = -1))
	}

	@Test
	fun prevPastFirstChapterDoesNotMove() {
		val cursor = ChapterSwitchCursor()
		cursor.settle(10L)
		assertNull(cursor.resolveRelative(chapters, liveChapterId = 10L, delta = -1))
	}

	@Test
	fun fallsBackToLiveStateWhenNotAnchored() {
		val cursor = ChapterSwitchCursor()
		assertEquals(30L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
	}

	@Test
	fun returnsNullWhenBaseUnknown() {
		val cursor = ChapterSwitchCursor()
		assertNull(cursor.resolveRelative(chapters, liveChapterId = null, delta = 1))
	}

	@Test
	fun staleAnchorFallsBackToLiveStateAfterBranchChange() {
		val cursor = ChapterSwitchCursor()
		// anchor points at a chapter that no longer exists in the current branch
		cursor.settle(999L)
		assertEquals(30L, cursor.resolveRelative(chapters, liveChapterId = 20L, delta = 1))
	}
}
