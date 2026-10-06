package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class TrackerRequestGateTest {
    private fun response(code: Int, vararg headers: Pair<String, String>) = Response.Builder().request(Request.Builder()
        .url("https://example.org/").build()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    @Test fun concurrentLibraryAndProgressRequestsShareTheRequestBudget() = runTest {
        val gate = TrackerRequestGate(2_100) { testScheduler.currentTime }
        val times = List(3) { async { gate.execute { testScheduler.currentTime } } }.map { it.await() }
        assertEquals(listOf(0L, 2_100L, 4_200L), times)
    }

    @Test fun retryAfterSecondsAndServerResetPauseFollowingRequests() = runTest {
        val gate = TrackerRequestGate(100) { testScheduler.currentTime }
        gate.execute { gate.update(response(429, "Retry-After" to "10")) }
        assertEquals(10_000L, gate.execute { testScheduler.currentTime })
        gate.execute { gate.update(response(200, "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "30")) }
        assertEquals(30_000L, gate.execute { testScheduler.currentTime })
    }

    @Test fun missingRetryInformationUsesSixtySecondsAndHttpDatesAreSupported() = runTest {
        val gate = TrackerRequestGate(0) { testScheduler.currentTime }
        gate.execute { gate.update(response(429)) }
        assertEquals(60_000L, gate.execute { testScheduler.currentTime })
        assertEquals(60_000L, gate.retryDelay("Thu, 01 Jan 1970 00:02:00 GMT", 60_000))
        assertNull(gate.retryDelay("nonsense", 0)); assertEquals(0L, gate.retryDelay("-2", 0))
    }

    @Test fun canceledWaitingRequestsNeverRunTheirOperation() = runTest {
        val gate = TrackerRequestGate(60_000) { testScheduler.currentTime }
        gate.execute { }
        var ran = false
        val pending = async { gate.execute { ran = true } }
        pending.cancel(); pending.join()
        assertFalse(ran)
    }
}
