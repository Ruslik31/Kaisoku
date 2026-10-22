package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

class TransientRetryTest {

	@Test
	fun retriesTransientFailuresUpToTheLimit() = runTest {
		var calls = 0
		try {
			retryTransient(attempts = 3, delaysMs = longArrayOf(1, 1)) {
				calls++
				throw SocketTimeoutException()
			}
			fail("Expected the last failure")
		} catch (_: SocketTimeoutException) {
		}
		assertEquals(3, calls)
	}

	@Test
	fun returnsTheFirstSuccess() = runTest {
		var calls = 0
		val result = retryTransient(attempts = 3, delaysMs = longArrayOf(1, 1)) {
			calls++
			if (calls < 2) throw SocketTimeoutException() else "ok"
		}
		assertEquals("ok", result)
		assertEquals(2, calls)
	}

	@Test
	fun doesNotRetryOtherFailures() = runTest {
		var calls = 0
		try {
			retryTransient(attempts = 3, delaysMs = longArrayOf(1, 1)) {
				calls++
				throw IllegalStateException()
			}
		} catch (_: IllegalStateException) {
		}
		assertEquals(1, calls)
	}

	@Test
	fun doesNotSwallowCancellation() = runTest {
		var calls = 0
		try {
			retryTransient(attempts = 3, delaysMs = longArrayOf(1, 1), isTransient = { true }) {
				calls++
				throw CancellationException()
			}
		} catch (_: CancellationException) {
		}
		assertEquals(1, calls)
	}
}
