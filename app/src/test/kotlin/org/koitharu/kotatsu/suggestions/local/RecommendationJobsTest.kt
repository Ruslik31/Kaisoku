package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RecommendationJobsTest {
    @Test fun concurrentScreensShareOneRequest() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        val response = CompletableDeferred<Unit>()
        var requests = 0
        val one = launch { jobs.run("explore", "current") { requests++; response.await() } }
        val two = launch { jobs.run("explore", "current") { requests++; response.await() } }
        runCurrent()
        assertEquals(1, requests)
        response.complete(Unit)
        one.join(); two.join()
    }

    @Test fun leavingOneScreenRetainsTheOtherScreensRequest() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        val response = CompletableDeferred<Unit>()
        var released = false
        val one = launch { jobs.run("details", "current") { try { response.await() } finally { released = true } } }
        val two = launch { jobs.run("details", "current") { fail("Should coalesce") } }
        runCurrent()
        one.cancelAndJoin()
        assertFalse(released)
        response.complete(Unit)
        two.join()
        assertTrue(released)
    }

    @Test fun leavingTheLastScreenCancelsPendingIo() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        var released = false
        val screen = launch { jobs.run("details", "current") { try { awaitCancellation() } finally { released = true } } }
        runCurrent()
        screen.cancelAndJoin()
        runCurrent()
        assertTrue(released)
    }

    @Test fun legacySwitchCancelsObsoleteRequestsButSameGenerationDoesNot() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        val screen = async { jobs.run("details", "new-engine") { awaitCancellation() } }
        runCurrent()
        jobs.invalidate("new-engine")
        assertTrue(screen.isActive)
        jobs.invalidate("legacy")
        runCurrent()
        assertTrue(screen.isCancelled)
    }

    @Test fun replacingARequestDoesNotLetItsOldCleanupCancelTheNewOne() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        val old = async { jobs.run("explore", "old") { awaitCancellation() } }
        runCurrent()
        val release = CompletableDeferred<Unit>()
        val current = async { jobs.run("explore", "new") { release.await() } }
        runCurrent()
        assertTrue(old.isCancelled)
        assertTrue(current.isActive)
        release.complete(Unit)
        current.await()
    }

    @Test fun aFailedRequestCanBeRetriedWithoutPoisoningItsContext() = runTest {
        val jobs = RecommendationJobs(kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext +
            kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job])))
        assertTrue(runCatching { jobs.run("explore", "current") { throw java.io.IOException("offline") } }.isFailure)
        var retried = false
        jobs.run("explore", "current") { retried = true }
        assertTrue(retried)
    }

    @Test fun operationLimitHoldsUnderRealConcurrency() {
        for (limit in listOf(8, 16)) {
            val budget = RecommendationBudget(limit)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val claims = executor.invokeAll((0..99).map { Callable { budget.claim() } }).count { it.get() }
                assertEquals(limit, claims)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun sourceAndPublicOperationsShareTwoSlotsAndCancellationReleasesThem() = runTest {
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val one = launch { RecommendationOperations.run { entered++; release.await() } }
        val two = launch { RecommendationOperations.run { entered++; release.await() } }
        val third = launch { RecommendationOperations.run { entered++ } }
        runCurrent()
        assertEquals(2, entered)
        one.cancelAndJoin()
        third.join()
        assertEquals(3, entered)
        release.complete(Unit)
        two.join()
    }
}
