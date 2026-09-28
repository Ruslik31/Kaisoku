package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterNavigationOrderTest {

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
}
