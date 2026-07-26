package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressTest {

	@Test
	fun finalPageOfFinalChapterIsExactlyComplete() {
		assertEquals(
			1f,
			calculateReaderPercent(
				chapterIndex = 96,
				chaptersCount = 97,
				pageIndex = 16,
				pagesCount = 17,
			),
		)
	}

	@Test
	fun pageBeforeEndIsNotCompleted() {
		val percent = calculateReaderPercent(
			chapterIndex = 96,
			chaptersCount = 97,
			pageIndex = 15,
			pagesCount = 17,
		)

		assertTrue(percent < 1f)
	}
}
