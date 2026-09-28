package org.koitharu.kotatsu.reader.ui.pager

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class ReaderChapterButtonsTest {
	private val chapter = MangaChapter(1L, "Chapter", 1f, 0, "https://example.org/1", null,
		0L, null, MangaParserSource.READMANGA_RU)
	private fun state(index: Int, reversed: Boolean, count: Int = 3) =
		ReaderUiState("Title", chapter, index, count, 0, 1, 0f, false, chaptersReversed = reversed)

	@Test
	fun reversedEndpointsKeepTheButtonForTheActualNeighborEnabled() {
		assertTrue(state(2, true).hasNextChapter())
		assertFalse(state(2, true).hasPreviousChapter())
		assertFalse(state(0, true).hasNextChapter())
		assertTrue(state(0, true).hasPreviousChapter())
		assertEquals(3, state(2, true).chapterNumber)
	}

	@Test
	fun normalOrderAndMissingChaptersHaveCorrectBoundaries() {
		assertTrue(state(0, false).hasNextChapter())
		assertFalse(state(0, false).hasPreviousChapter())
		assertFalse(state(2, false).hasNextChapter())
		assertTrue(state(2, false).hasPreviousChapter())
		for (reverse in listOf(false, true)) {
			assertFalse(state(0, reverse, 1).hasNextChapter())
			assertFalse(state(0, reverse, 1).hasPreviousChapter())
			assertFalse(state(-1, reverse).hasNextChapter())
			assertFalse(state(-1, reverse).hasPreviousChapter())
		}
	}
}
