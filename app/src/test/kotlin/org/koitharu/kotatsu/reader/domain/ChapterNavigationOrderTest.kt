package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterNavigationOrderTest {

    @Test fun progressIndexFollowsDirectionAndPreservesMissingChapter() {
        assertEquals(0, readingOrderIndex(4, 5, true))
        assertEquals(4, readingOrderIndex(0, 5, true))
        assertEquals(4, readingOrderIndex(4, 5, false))
        assertEquals(-1, readingOrderIndex(-1, 5, true))
    }

	@Test
	fun reversedNavigationUsesTheVisibleChapterSequence() {
		val sourceOrder = listOf(10L, 20L, 30L, 40L)
		val reversed = itemsInReadingOrder(sourceOrder, reversed = true)
		val cursor = ChapterSwitchCursor()
		cursor.settle(30L)

		assertEquals(listOf(40L, 30L, 20L, 10L), reversed)
		assertEquals(20L, cursor.resolveRelative(reversed, liveChapterId = 30L, delta = 1))

		val previousCursor = ChapterSwitchCursor().apply { settle(30L) }
		assertEquals(40L, previousCursor.resolveRelative(reversed, liveChapterId = 30L, delta = -1))
	}

	@Test
	fun normalNavigationKeepsSourceOrder() {
		val sourceOrder = listOf(10L, 20L, 30L)
		assertEquals(sourceOrder, itemsInReadingOrder(sourceOrder, reversed = false))
	}

	@Test
	fun directionChangeReanchorsButtonsToVisibleChapter() {
		val sourceOrder = listOf(10L, 20L, 30L, 40L, 50L)
		val cursor = ChapterSwitchCursor().apply { settle(10L) }
		// The reader has scrolled since the old cursor was settled.
		cursor.settle(40L)
		val reversed = itemsInReadingOrder(sourceOrder, reversed = true)
		assertEquals(30L, cursor.resolveRelative(reversed, liveChapterId = 40L, delta = 1))
		assertEquals(20L, cursor.resolveRelative(reversed, liveChapterId = 40L, delta = 1))
		assertEquals(30L, cursor.resolveRelative(reversed, liveChapterId = 40L, delta = -1))
		cursor.settle(40L)
		assertEquals(50L, cursor.resolveRelative(sourceOrder, liveChapterId = 40L, delta = 1))
	}
}
