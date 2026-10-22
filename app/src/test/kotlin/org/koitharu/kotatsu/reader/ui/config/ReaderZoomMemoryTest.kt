package org.koitharu.kotatsu.reader.ui.config

import org.junit.Assert.*
import org.junit.Test

class ReaderZoomMemoryTest {
    @Test fun equalRelativeZoomFitsDifferentPageResolutions() {
        assertEquals(2f, relativeReaderZoom(.5f, .25f))
        assertEquals(2f, relativeReaderZoom(1f, .5f))
        assertEquals(.4f, rememberedPageScale(2f, .2f, .2f, 2f))
        assertEquals(1f, rememberedPageScale(2f, .5f, .5f, 4f))
    }

    @Test fun tallWideAndRotatedPagesUseTheirOwnFitAndBounds() {
        assertEquals(1f, rememberedPageScale(4f, .25f, .25f, 2f))
        assertEquals(.8f, rememberedPageScale(4f, .4f, .4f, .8f))
        assertEquals(.6f, rememberedPageScale(.5f, .3f, .6f, 2f))
        assertNull(rememberedPageScale(null, .25f, .25f, 2f))
    }

    @Test fun malformedNumbersNeverProduceAnInvalidTransform() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            assertNull(relativeReaderZoom(value, 1f))
            assertNull(relativeReaderZoom(1f, value))
            assertNull(rememberedPageScale(value, 1f, 1f, 2f))
            assertNull(rememberedPageScale(2f, value, 1f, 2f))
            assertNull(rememberedPageScale(2f, 1f, value, 2f))
        }
        assertNull(rememberedPageScale(2f, 1f, 2f, 1f))
        assertNull(rememberedPageScale(2f, Float.MAX_VALUE, 1f, 2f))
    }

    @Test fun intermediateDoubleTapAnimationFramesDoNotCountAsCompletedZoom() {
        assertFalse(isDoubleTapZoomComplete(1.1f, 1f, 4f, 2f))
        assertFalse(isDoubleTapZoomComplete(1.9f, 1f, 4f, 2f))
        assertTrue(isDoubleTapZoomComplete(2f, 1f, 4f, 2f))
        assertTrue(isDoubleTapZoomComplete(1f, 1f, 4f, 2f))
        assertTrue(isDoubleTapZoomComplete(4f, 1f, 4f, 8f))
        assertFalse(isDoubleTapZoomComplete(Float.NaN, 1f, 4f, 2f))
    }

    @Test fun presentationScopesDoNotCollideOrChangeReaderModeIds() {
        assertEquals(5, ReaderZoomScope.entries.map { ReaderZoomMemory.key(it) }.toSet().size)
        assertEquals(listOf(1, 3, 4, 2), org.koitharu.kotatsu.core.prefs.ReaderMode.entries.map { it.id })
    }

    @Test fun numericNativeBackupRepresentationsKeepTheSameZoom() {
        for (value in listOf(2, 2L, 2f, 2.0)) assertEquals(2f, restoredZoomRatio(value))
        assertEquals(1.5f, restoredZoomRatio(1.5))
    }

    @Test fun invalidBackupRepresentationsDoNotBecomeTransforms() {
        for (value in listOf(null, "2", "NaN", false, Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 99999999L)) {
            assertNull(restoredZoomRatio(value))
        }
    }
}
