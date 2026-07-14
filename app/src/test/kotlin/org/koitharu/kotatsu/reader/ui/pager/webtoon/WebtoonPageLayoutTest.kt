package org.koitharu.kotatsu.reader.ui.pager.webtoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonPageLayoutTest {

	@Test
	fun delayedRestoreRequiresTheExactChapterAndPage() {
		val target = WebtoonPageKey(chapterId = 18L, pageId = 11L)
		assertTrue(isRestoreTarget(target, actualChapterId = 18L, actualPageId = 11L))
		assertFalse(isRestoreTarget(target, actualChapterId = 19L, actualPageId = 11L))
		assertFalse(isRestoreTarget(target, actualChapterId = 18L, actualPageId = 12L))
	}

	@Test
	fun unloadedPageScrollMapsProportionally() {
		assertEquals(0, calculateUnloadedPageScrollPercent(itemTop = 100, itemHeight = 1000))
		assertEquals(0, calculateUnloadedPageScrollPercent(itemTop = 0, itemHeight = 1000))
		assertEquals(3_700, calculateUnloadedPageScrollPercent(itemTop = -370, itemHeight = 1000))
		assertEquals(10_000, calculateUnloadedPageScrollPercent(itemTop = -1200, itemHeight = 1000))
	}

	@Test
	fun cachedDimensionsKeepShortPanelPlaceholderHeight() {
		assertEquals(
			540,
			calculateScaledPageHeight(
				sourceWidth = 1000,
				sourceHeight = 600,
				targetWidth = 900,
				maximumHeight = 2000,
			),
		)
	}

	@Test
	fun cachedTallPageHeightIsCappedToViewport() {
		assertEquals(
			2000,
			calculateScaledPageHeight(
				sourceWidth = 1000,
				sourceHeight = 5000,
				targetWidth = 900,
				maximumHeight = 2000,
			),
		)
	}

	@Test
	fun decodedDimensionsSurviveHolderReuse() {
		val key = WebtoonPageKey(chapterId = 18L, pageId = 11L)
		val cache = WebtoonPageSizeCache()

		cache.put(key, width = 1000, height = 600)

		assertEquals(WebtoonImageSize(width = 1000, height = 600), cache[key])
	}
}
