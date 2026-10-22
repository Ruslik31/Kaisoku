package org.koitharu.kotatsu.core.ui.list

import org.junit.Assert.assertEquals
import org.junit.Test

class ListLongPressTimeoutTest {
    @Test fun defaultAndInvalidValuesLeaveThePlatformInControl() {
        for (requested in listOf(0, -1, 500, Int.MAX_VALUE)) assertEquals(0, listLongPressTimeout(requested, 500))
    }
    @Test fun customChoiceCannotShortenAccessibilityTiming() {
        assertEquals(2000, listLongPressTimeout(750, 2000))
        assertEquals(1000, listLongPressTimeout(1000, 500))
        assertEquals(1500, listLongPressTimeout(1500, 500))
    }
}
