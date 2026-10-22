package org.koitharu.kotatsu.reader.ui.pager

import org.junit.Assert.*
import org.junit.Test

class PageRotationTest {
    @Test fun onlyWideValidPagesRotateAndTheDefaultIsUnchanged() {
        for (rotation in listOf(0, 90, 270)) {
            assertEquals(0, PageRotation.orientation(500, 1000, rotation))
            assertEquals(0, PageRotation.orientation(1000, 1000, rotation))
            assertEquals(0, PageRotation.orientation(1000, 0, rotation))
            assertEquals(rotation, PageRotation.orientation(2000, 1000, rotation))
        }
        assertEquals(0, PageRotation.orientation(2000, 1000, 45))
    }
    @Test fun clockwiseAndCounterclockwiseMapKnownMarkerCorners() {
        assertEquals(1f to 0f, PageRotation.toDisplay(0f, 0f, 90))
        assertEquals(0f to 1f, PageRotation.toDisplay(0f, 0f, 270))
        assertEquals(0.8f to 0.3f, PageRotation.toDisplay(0.3f, 0.2f, 90))
    }
    @Test fun viewportAndMarkersRoundTripWithoutChangingSourceCoordinates() {
        for (rotation in listOf(0, 90, 180, 270)) for (x in listOf(0f, 0.15f, 0.5f, 1f)) for (y in listOf(0f, 0.7f, 1f)) {
            val display = PageRotation.toDisplay(x, y, rotation)
            val original = PageRotation.toOriginal(display.first, display.second, rotation)
            assertEquals(x, original.first, 0.00001f)
            assertEquals(y, original.second, 0.00001f)
        }
    }
}
