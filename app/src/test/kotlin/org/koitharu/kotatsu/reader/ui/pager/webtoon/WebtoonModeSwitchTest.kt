package org.koitharu.kotatsu.reader.ui.pager.webtoon

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test

class WebtoonModeSwitchTest {

	@Test
	fun visibleReadingPageWinsOverPartlyVisiblePreviousPage() {
		assertEquals(
			6,
			resolveWebtoonModeSwitchPosition(
				firstVisiblePosition = 5,
				readingLinePosition = 6,
				itemCount = 12,
				isAtAbsoluteBottom = false,
			),
		)
	}

	@Test
	fun firstVisiblePageIsOnlyAFallbackWhenReadingLineIsUnavailable() {
		assertEquals(
			5,
			resolveWebtoonModeSwitchPosition(
				firstVisiblePosition = 5,
				readingLinePosition = RecyclerView.NO_POSITION,
				itemCount = 12,
				isAtAbsoluteBottom = false,
			),
		)
	}

	@Test
	fun absoluteBottomKeepsLastPageForFullProgress() {
		assertEquals(
			11,
			resolveWebtoonModeSwitchPosition(
				firstVisiblePosition = 9,
				readingLinePosition = 10,
				itemCount = 12,
				isAtAbsoluteBottom = true,
			),
		)
	}
}
