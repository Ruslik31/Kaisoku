package org.koitharu.kotatsu.reader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRetryTest {
	@Test fun onlyTransientProviderResponsesAreRetried() {
		assertTrue(TranslationRetry.shouldRetry(408))
		assertTrue(TranslationRetry.shouldRetry(429))
		assertTrue(TranslationRetry.shouldRetry(500))
		assertTrue(TranslationRetry.shouldRetry(503))
		assertFalse(TranslationRetry.shouldRetry(400))
		assertFalse(TranslationRetry.shouldRetry(403))
	}

	@Test fun exponentialBackoffUsesJitterAndHasABound() {
		assertEquals(500L, TranslationRetry.delayMillis(0, jitterSample = 0.0))
		assertEquals(1_500L, TranslationRetry.delayMillis(0, jitterSample = 1.0))
		assertEquals(3_000L, TranslationRetry.delayMillis(1, jitterSample = 1.0))
		assertEquals(60_000L, TranslationRetry.delayMillis(20, jitterSample = 1.0))
	}

	@Test fun retryAfterAcceptsSecondsAndHttpDates() {
		assertEquals(5_000L, TranslationRetry.delayMillis(0, "5", nowMillis = 0L, jitterSample = 0.0))
		assertEquals(
			9_000L,
			TranslationRetry.delayMillis(0, "Thu, 01 Jan 1970 00:00:10 GMT", nowMillis = 1_000L),
		)
		assertEquals(60_000L, TranslationRetry.delayMillis(0, "120", nowMillis = 0L))
	}
}
