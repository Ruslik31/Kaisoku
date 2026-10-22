package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecommendationRefreshPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun automaticRefreshWaitsSixHoursIncludingAnEmptyOrFailedResult() {
        for (age in listOf(0L, 60_000L, RecommendationRefreshPolicy.INTERVAL - 1)) {
            assertFalse(RecommendationRefreshPolicy.shouldRefresh(now - age, 0, now, false))
        }
        assertTrue(RecommendationRefreshPolicy.shouldRefresh(now - RecommendationRefreshPolicy.INTERVAL, 0, now, false))
    }

    @Test fun explicitRefreshCanRetryAfterOneMinuteWithoutBypassingAttemptCooldown() {
        assertFalse(RecommendationRefreshPolicy.shouldRefresh(now - 59_999, 0, now, true))
        assertTrue(RecommendationRefreshPolicy.shouldRefresh(now - 60_000, 0, now, true))
        assertFalse(RecommendationRefreshPolicy.shouldRefresh(null, now + 1, now, true))
        assertTrue(RecommendationRefreshPolicy.shouldRefresh(null, now, now, true))
    }

    @Test fun aCancelledFirstAttemptDoesNotCauseRepeatedNetworkWorkOnRecreation() {
        val retryUntil = now + RecommendationRefreshPolicy.RETRY_INTERVAL
        repeat(20) { assertFalse(RecommendationRefreshPolicy.shouldRefresh(null, retryUntil, now + it, false)) }
        assertTrue(RecommendationRefreshPolicy.shouldRefresh(null, retryUntil, retryUntil, false))
    }

    @Test fun missingOrExplicitlyInvalidatedResultsCanRefresh() {
        assertTrue(RecommendationRefreshPolicy.shouldRefresh(null, 0, now, false))
        assertTrue(RecommendationRefreshPolicy.shouldRefresh(0, 0, now, false))
        assertFalse(RecommendationRefreshPolicy.shouldRefresh(now + 60_000, 0, now, false))
    }

    @Test fun concurrentAndSubsequentScreenGenerationsReuseOneSuccessfulRefresh() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        var lastRefresh: Long? = null
        var calls = 0
        repeat(10) {
            launch {
                jobs.run("explore", "initial") {
                    if (RecommendationRefreshPolicy.shouldRefresh(lastRefresh, 0, now, false)) {
                        calls++
                        lastRefresh = now
                    }
                }
            }
        }
        testScheduler.runCurrent()
        for (generation in listOf("media-filter", "language-filter", "recreation", "periodic-worker")) {
            jobs.run("explore", generation) {
                if (RecommendationRefreshPolicy.shouldRefresh(lastRefresh, 0, now + 60_000, false)) calls++
            }
        }
        assertEquals(1, calls)
    }
}
