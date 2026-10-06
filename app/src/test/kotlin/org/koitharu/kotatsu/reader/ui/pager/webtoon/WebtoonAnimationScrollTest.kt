package org.koitharu.kotatsu.reader.ui.pager.webtoon

import org.junit.Assert.assertEquals
import org.junit.Test

class WebtoonAnimationScrollTest {

    @Test
    fun savedOffsetsSurviveSwitchingBetweenTiledAndAnimatedPages() {
        // Both presentations show a 4500px panel. SSIV caps its view at the 2000px viewport,
        // while the animation's view has the full height and no internal scroll.
        for (percent in listOf(0, 3700, 7000, 10_000)) {
            val tiled = calculateWebtoonScrollPosition(percent, internalRange = 2500, itemHeight = 2000)
            val animated = calculateWebtoonScrollPosition(percent, internalRange = 0, itemHeight = 4500)
            assertEquals(tiled.internalScroll - tiled.itemTop, animated.internalScroll - animated.itemTop)
            assertEquals(0, animated.internalScroll)
        }
        assertEquals(WebtoonScrollPosition(2500, -650), calculateWebtoonScrollPosition(7000, 2500, 2000))
        assertEquals(WebtoonScrollPosition(0, -3150), calculateWebtoonScrollPosition(7000, 0, 4500))
    }

    @Test
    fun shortPanelUsesTheSameGeometryForBothPresentations() {
        assertEquals(WebtoonScrollPosition(0, -199), calculateWebtoonScrollPosition(3700, 0, 540))
        assertEquals(WebtoonScrollPosition(0, -540), calculateWebtoonScrollPosition(10_000, 0, 540))
    }

    @Test
    fun invalidSavedOffsetsAndUnresolvedDimensionsAreBounded() {
        assertEquals(WebtoonScrollPosition(0, 0), calculateWebtoonScrollPosition(-1, 2500, 2000))
        assertEquals(WebtoonScrollPosition(0, -4500), calculateWebtoonScrollPosition(Int.MAX_VALUE, 0, 4500))
        assertEquals(WebtoonScrollPosition(0, 0), calculateWebtoonScrollPosition(5000, -1, -1))
    }

    @Test
    fun largeDimensionsDoNotOverflowIntoNegativeProgress() {
        val position = calculateWebtoonScrollPosition(10_000, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(WebtoonScrollPosition(Int.MAX_VALUE, 0), position)
    }
}
