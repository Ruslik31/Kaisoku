package org.koitharu.kotatsu.reader.ui.pager.webtoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonScrollConsumptionTest {

	@Test
	fun aPartlyVisibleNeighbourCannotConsumeMoreThanTheRemainingGesture() {
		assertEquals(80, remainingWebtoonScroll(100, 20, -50))
		assertEquals(-80, remainingWebtoonScroll(-100, -20, -50))
	}

	@Test
	fun scrollingFirstPaysForTheGapToTheNeighbourInBothDirections() {
		assertEquals(30, remainingWebtoonScroll(100, 20, 50))
		assertEquals(-30, remainingWebtoonScroll(-100, -20, 50))
		assertEquals(0, remainingWebtoonScroll(100, 20, 100))
		assertEquals(0, remainingWebtoonScroll(-100, -20, 100))
	}

	@Test
	fun zeroAndFullyConsumedGesturesDoNotScrollAnotherPage() {
		assertEquals(0, remainingWebtoonScroll(0, 0, -100))
		assertEquals(0, remainingWebtoonScroll(100, 100, -100))
		assertEquals(0, remainingWebtoonScroll(-100, -100, -100))
	}

	@Test
	fun geometryFixturesNeverAmplifyOrReverseTheRemainingMovement() {
		for (direction in listOf(-1, 1)) {
			for (consumed in 0..100) {
				for (gap in -200..200 step 10) {
					val remainder = remainingWebtoonScroll(100 * direction, consumed * direction, gap)
					assertTrue(remainder * direction in 0..(100 - consumed))
				}
			}
		}
	}
}
