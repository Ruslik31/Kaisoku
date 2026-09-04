package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementMatchPolicyTest {

	@Test
	fun normalizesPunctuationCaseAndWidth() {
		assertTrue(ReplacementMatchPolicy.isExact(listOf("ＭＡＮＧＡ: One"), listOf("manga one")))
	}

	@Test
	fun requiresConfidenceAndAnUnambiguousLead() {
		assertTrue(ReplacementMatchPolicy.accepts(false, 0.94f, 0.80f))
		assertFalse(ReplacementMatchPolicy.accepts(false, 0.91f, null))
		assertFalse(ReplacementMatchPolicy.accepts(false, 0.94f, 0.91f))
		assertTrue(ReplacementMatchPolicy.accepts(true, 0.5f, 0.49f))
	}
}
