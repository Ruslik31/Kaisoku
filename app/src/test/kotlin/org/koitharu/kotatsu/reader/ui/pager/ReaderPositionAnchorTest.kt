package org.koitharu.kotatsu.reader.ui.pager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPositionAnchorTest {

	@Test
	fun appendedPagesKeepRecyclerViewAnchor() {
		assertFalse(shouldReanchorAfterPageListUpdate(oldPosition = 10, newPosition = 10))
	}

	@Test
	fun prependedOrTrimmedPagesRequireReanchor() {
		assertTrue(shouldReanchorAfterPageListUpdate(oldPosition = 10, newPosition = 24))
		assertTrue(shouldReanchorAfterPageListUpdate(oldPosition = 24, newPosition = 10))
	}

	@Test
	fun missingCurrentPageDoesNotRequestInvalidRestore() {
		assertFalse(shouldReanchorAfterPageListUpdate(oldPosition = -1, newPosition = 10))
		assertFalse(shouldReanchorAfterPageListUpdate(oldPosition = 10, newPosition = -1))
	}
}
