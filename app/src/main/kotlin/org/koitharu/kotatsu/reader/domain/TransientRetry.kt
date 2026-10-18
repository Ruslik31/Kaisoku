package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.core.util.ext.isNetworkError

internal const val TRANSIENT_RETRY_ATTEMPTS = 3
private val TRANSIENT_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)

/** Repeats [block] after short pauses while it fails with a transient network error. */
internal suspend fun <T> retryTransient(
	attempts: Int = TRANSIENT_RETRY_ATTEMPTS,
	delaysMs: LongArray = TRANSIENT_RETRY_DELAYS_MS,
	isTransient: (Throwable) -> Boolean = Throwable::isNetworkError,
	block: suspend () -> T,
): T {
	var attempt = 0
	while (true) {
		try {
			return block()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			attempt++
			if (attempt >= attempts || !isTransient(e)) {
				throw e
			}
			delay(delaysMs.getOrElse(attempt - 1) { delaysMs.lastOrNull() ?: 0L })
		}
	}
}
