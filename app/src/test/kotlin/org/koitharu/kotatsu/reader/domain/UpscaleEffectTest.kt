package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleEffectTest {

	@Test
	fun appliesOnlyToSupportedLowResolutionPages() {
		assertTrue(UpscaleEffect.shouldApply(true, true, false, 1.51f))
		assertFalse(UpscaleEffect.shouldApply(false, true, false, 3f))
		assertFalse(UpscaleEffect.shouldApply(true, false, false, 3f))
		assertFalse(UpscaleEffect.shouldApply(true, true, true, 3f))
		assertFalse(UpscaleEffect.shouldApply(true, true, false, 1.5f))
	}

	@Test
	fun increasesPassesWithScale() {
		assertEquals(2, UpscaleEffect.passCount(1.51f))
		assertEquals(3, UpscaleEffect.passCount(2f))
		assertEquals(4, UpscaleEffect.passCount(3f))
	}
}
