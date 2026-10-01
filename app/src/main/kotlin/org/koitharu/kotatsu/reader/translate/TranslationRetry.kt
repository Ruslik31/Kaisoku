package org.koitharu.kotatsu.reader.translate

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Small, bounded retry policy for transient translation-provider failures. */
internal object TranslationRetry {
	private const val BASE_DELAY_MS = 1_000L
	private const val MAX_DELAY_MS = 60_000L

	fun shouldRetry(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode in 500..599

	fun delayMillis(
		attempt: Int,
		retryAfter: String? = null,
		nowMillis: Long = System.currentTimeMillis(),
		jitterSample: Double = Random.nextDouble(),
	): Long {
		parseRetryAfterMillis(retryAfter, nowMillis)?.let { return it.coerceIn(0L, MAX_DELAY_MS) }
		val exponent = attempt.coerceIn(0, 10)
		val base = (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
		// Full-ish jitter prevents parallel clients from synchronizing retries after an outage.
		val factor = 0.5 + jitterSample.coerceIn(0.0, 1.0)
		return (base * factor).toLong().coerceIn(500L, MAX_DELAY_MS)
	}

	private fun parseRetryAfterMillis(value: String?, nowMillis: Long): Long? {
		val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		header.toLongOrNull()?.let { seconds ->
			return seconds.coerceIn(0L, MAX_DELAY_MS / 1_000L) * 1_000L
		}
		val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
			isLenient = false
			timeZone = TimeZone.getTimeZone("GMT")
		}
		return runCatching { (format.parse(header)?.time ?: return null) - nowMillis }
			.getOrNull()?.coerceAtLeast(0L)?.coerceAtMost(MAX_DELAY_MS)
	}
}
