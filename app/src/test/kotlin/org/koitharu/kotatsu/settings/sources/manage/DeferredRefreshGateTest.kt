package org.koitharu.kotatsu.settings.sources.manage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredRefreshGateTest {

	@Test
	fun invalidationsAreCoalescedUntilTheDragEnds() {
		val gate = DeferredRefreshGate()
		gate.pause()

		assertFalse(gate.requestRefresh())
		assertFalse(gate.requestRefresh())
		assertTrue(gate.resume())
		assertFalse(gate.resume())
	}

	@Test
	fun nestedInteractionsWaitForTheFinalResume() {
		val gate = DeferredRefreshGate()
		gate.pause()
		gate.pause()
		assertFalse(gate.requestRefresh())

		assertFalse(gate.resume())
		assertTrue(gate.resume())
		assertTrue(gate.requestRefresh())
	}

	@Test
	fun interactionWithoutInvalidationDoesNotRefresh() {
		val gate = DeferredRefreshGate()
		gate.pause()
		assertFalse(gate.resume())
	}
}
