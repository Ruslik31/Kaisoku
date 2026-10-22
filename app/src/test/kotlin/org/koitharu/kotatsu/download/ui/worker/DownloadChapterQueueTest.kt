package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DownloadChapterQueueTest {

    @Test
    fun differentWorkersAndSourcesShareOneChapterLimit() = runTest {
        val queue = DownloadChapterQueue { 2 }
        val finish = CompletableDeferred<Unit>()
        val admitted = mutableListOf<String>()
        var active = 0
        var maximum = 0
        val sources = listOf("source-a", "source-b", "source-a", "source-c", "source-b")
        val jobs = sources.map { source ->
            launch {
                queue.acquire().use {
                    active++
                    maximum = maxOf(maximum, active)
                    admitted += source
                    try {
                        finish.await()
                    } finally {
                        active--
                    }
                }
            }
        }
        runCurrent()
        assertEquals(sources.take(2), admitted)
        assertEquals(2, active)
        finish.complete(Unit)
        jobs.joinAll()
        assertEquals(2, maximum)
        assertEquals(sources, admitted)
        assertEquals(0, active)
    }

    @Test
    fun defaultLimitDoesNotReduceExistingWorkerConcurrency() = runTest {
        val queue = DownloadChapterQueue { 0 }
        val permits = List(12) { withTimeout(1_000L) { queue.acquire() } }
        permits.forEach { it.close() }
    }

    @Test
    fun cancelledQueuedChapterDoesNotBlockTheNextWorker() = runTest {
        val queue = DownloadChapterQueue { 1 }
        val first = queue.acquire()
        var cancelledEntered = false
        var nextEntered = false
        val cancelled = launch { queue.acquire().use { cancelledEntered = true } }
        val next = launch { queue.acquire().use { nextEntered = true } }
        runCurrent()
        cancelled.cancelAndJoin()
        assertFalse(cancelledEntered)
        assertFalse(nextEntered)
        first.close()
        next.join()
        assertTrue(nextEntered)
    }

    @Test
    fun cancellationAfterAdmissionBeforeResumeReleasesTheSlot() = runTest {
        val queue = DownloadChapterQueue { 1 }
        val first = queue.acquire()
        var cancelledEntered = false
        val cancelled = launch { queue.acquire().use { cancelledEntered = true } }
        runCurrent()
        first.close()
        // Admission completed the deferred, but this worker has not resumed on its dispatcher yet.
        cancelled.cancelAndJoin()
        assertFalse(cancelledEntered)
        withTimeout(1_000L) { queue.acquire() }.close()
    }

    @Test
    fun cancellingAnActiveChapterReleasesItsSlot() = runTest {
        val queue = DownloadChapterQueue { 1 }
        val finish = CompletableDeferred<Unit>()
        val active = launch { queue.acquire().use { finish.await() } }
        var nextEntered = false
        runCurrent()
        val next = launch { queue.acquire().use { nextEntered = true } }
        runCurrent()
        assertFalse(nextEntered)
        active.cancelAndJoin()
        next.join()
        assertTrue(nextEntered)
    }

    @Test
    fun failureAndRepeatedCloseCannotLeakOrMultiplySlots() = runTest {
        val queue = DownloadChapterQueue { 1 }
        val first = queue.acquire()
        var failed = false
        val failure = launch {
            try {
                queue.acquire().use { throw IOException("chapter failed") }
            } catch (_: IOException) {
                failed = true
            }
        }
        runCurrent()
        first.close()
        first.close()
        failure.join()
        assertTrue(failed)
        val next = withTimeout(1_000L) { queue.acquire() }
        var extraEntered = false
        val extra = launch { queue.acquire().use { extraEntered = true } }
        runCurrent()
        assertFalse(extraEntered)
        next.close()
        extra.join()
        assertTrue(extraEntered)
    }

    @Test
    fun reducingAnUnboundedLimitCountsAllAlreadyAdmittedChapters() = runTest {
        var configuredLimit = 0
        val queue = DownloadChapterQueue { configuredLimit }
        val existing = List(3) { queue.acquire() }
        configuredLimit = 1
        queue.onLimitChanged()
        var nextEntered = false
        val next = launch { queue.acquire().use { nextEntered = true } }
        runCurrent()
        existing.take(2).forEach { it.close() }
        runCurrent()
        assertFalse(nextEntered)
        existing.last().close()
        next.join()
        assertTrue(nextEntered)
    }

    @Test
    fun raisingOrDisablingTheLimitWakesWaitingWorkersImmediately() = runTest {
        var configuredLimit = 1
        val queue = DownloadChapterQueue { configuredLimit }
        val first = queue.acquire()
        val finish = CompletableDeferred<Unit>()
        var entered = 0
        val jobs = List(4) {
            launch { queue.acquire().use { entered++; finish.await() } }
        }
        runCurrent()
        assertEquals(0, entered)
        configuredLimit = 3
        queue.onLimitChanged()
        runCurrent()
        assertEquals(2, entered)
        configuredLimit = 0
        queue.onLimitChanged()
        runCurrent()
        assertEquals(4, entered)
        first.close()
        finish.complete(Unit)
        jobs.joinAll()
    }

    @Test
    fun admittedPausedChapterRetainsItsSlotUntilSkippedOrResumed() = runTest {
        val queue = DownloadChapterQueue { 1 }
        val pausing = PausingHandle().apply { pause() }
        val paused = launch(pausing) { queue.acquire().use { pausing.awaitResumed() } }
        runCurrent()
        var nextEntered = false
        val next = launch { queue.acquire().use { nextEntered = true } }
        runCurrent()
        assertFalse(nextEntered)
        pausing.skip()
        paused.join()
        next.join()
        assertTrue(nextEntered)
    }
}
