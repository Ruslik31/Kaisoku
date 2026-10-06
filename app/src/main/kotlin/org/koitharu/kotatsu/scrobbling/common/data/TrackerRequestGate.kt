package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TrackerHttpException(val code: Int, val retryAfterMillis: Long = 0L, detail: String = "") :
    IOException(if (code == 429) "Too many requests. Retry in ${(retryAfterMillis + 999) / 1000} seconds."
        else "Tracker HTTP $code${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")

/** Shared across library, search and progress calls for one service. */
class TrackerRequestGate(private val spacingMillis: Long, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
        delay((nextRequestAt - nowMillis()).coerceAtLeast(0L))
        nextRequestAt = nowMillis() + spacingMillis
        block()
    }

    fun update(response: Response) {
        val now = nowMillis()
        val retry = retryDelay(response.header("Retry-After"), now)
        val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
        val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
        if (response.code == 429) nextRequestAt = maxOf(nextRequestAt, now + (retry ?: 60_000L))
        if (remaining == 0 && reset != null) nextRequestAt = maxOf(nextRequestAt, reset)
    }

    internal fun retryDelay(value: String?, now: Long): Long? = value?.let {
        it.toLongOrNull()?.coerceAtLeast(0L)?.times(1000) ?: runCatching {
            (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
                .coerceAtLeast(0L)
        }.getOrNull()
    }
}
