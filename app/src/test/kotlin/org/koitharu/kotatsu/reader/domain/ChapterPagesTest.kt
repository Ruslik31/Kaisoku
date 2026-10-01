package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import kotlin.random.Random

class ChapterPagesTest {

	@Test
	fun getChaptersSize() {
		val pages = ChapterPages()
		pages.addFirst(1L, List(12) { page(1L) })
		pages.addFirst(2L, List(17) { page(2L) })
		assertEquals(2, pages.chaptersSize)
	}

	@Test
	fun removeFirst() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addLast(4L, List(2) { page(4L) })
		pages.removeFirst()
		assertEquals(2, pages.chaptersSize)
		assertEquals(17 + 2, pages.size)
	}

	@Test
	fun removeLast() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addLast(4L, List(2) { page(4L) })
		pages.removeLast()
		assertEquals(2, pages.chaptersSize)
		assertEquals(12 + 17, pages.size)
	}

	@Test
	fun clear() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addLast(4L, List(2) { page(4L) })
		pages.clear()
		assertEquals(0, pages.chaptersSize)
		assertEquals(0, pages.size)
		assertEquals(0, pages.size(1L))
		assertEquals(0, pages.size(2L))
		assertEquals(0, pages.size(4L))
	}

	@Test
	fun subList() {
		val pages = ChapterPages()
		pages.addLast(1L, List(12) { page(1L) })
		pages.addLast(2L, List(17) { page(2L) })
		pages.addFirst(4L, List(2) { page(4L) })
		val subList = pages.subList(2L)
		assertEquals(17, subList.size)
		assertEquals(2L, subList.first().chapterId)
		assertEquals(2L, subList.last().chapterId)
		assertTrue(subList.all { it.chapterId == 2L })
		assertEquals(subList.size, pages.size(2L))
	}

	@Test
	fun changingDirectionRetainsVisiblePagesAndDiscardsOldPreloads() {
		val pages = ChapterPages()
		val visible = List(12) { page(2L).copy(index = it) }
		pages.addLast(1L, List(3) { page(1L) })
		pages.addLast(2L, visible)
		pages.addLast(3L, List(4) { page(3L) })
		val anchor = visible[8]
		pages.retainVisibleChapter(2L, visible)
		assertEquals(1, pages.chaptersSize)
		assertEquals(visible, pages.toList())
		assertEquals(anchor, pages[8])
		// In reversed reading, chapter 1 now follows chapter 2 and can be preloaded again.
		assertTrue(pages.addLast(1L, List(3) { page(1L) }))
		assertEquals(anchor, pages[8])
	}

	@Test
	fun directionChangeUsesVisibleSnapshotAfterInterruptedLoadChangedBuffer() {
		val pages = ChapterPages()
		val visible = List(12) { page(2L).copy(index = it) }
		pages.addLast(3L, List(4) { page(3L) })
		pages.retainVisibleChapter(2L, visible)
		assertEquals(visible, pages.subList(2L))
		assertEquals(0, pages.size(3L))
	}

	private fun page(chapterId: Long) = ReaderPage(
		id = Random.nextLong(),
		url = "http://localhost",
		preview = null,
		chapterId = chapterId,
		index = Random.nextInt(),
		source = TestMangaSource,
	)
}
