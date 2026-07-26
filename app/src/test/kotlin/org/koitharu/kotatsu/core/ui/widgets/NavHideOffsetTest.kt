package org.koitharu.kotatsu.core.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class NavHideOffsetTest {

	@Test
	fun anchoredBarHidesByItsOwnHeight() {
		assertEquals(160f, calculateNavHideOffset(height = 160, bottomMargin = 0), 0f)
	}

	@Test
	fun floatingBarAlsoClearsItsBottomMargin() {
		// Without the margin the top of the pill would stay on screen after hiding.
		assertEquals(196f, calculateNavHideOffset(height = 160, bottomMargin = 36), 0f)
	}

	@Test
	fun unmeasuredBarReportsNoOffset() {
		assertEquals(0f, calculateNavHideOffset(height = 0, bottomMargin = 0), 0f)
	}

	@Test
	fun negativeValuesAreIgnored() {
		assertEquals(160f, calculateNavHideOffset(height = 160, bottomMargin = -12), 0f)
	}
}
