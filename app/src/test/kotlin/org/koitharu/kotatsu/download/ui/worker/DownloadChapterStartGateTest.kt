package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadChapterStartGateTest {

    @Test
    fun defaultDoesNotAddAnyDelay() = runTest {
        val gate = DownloadChapterStartGate { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        List(6) { launch { gate.awaitStart(0L); starts += testScheduler.currentTime } }.joinAll()
        assertEquals(List(6) { 0L }, starts)
    }

    @Test
    fun workersShareSpacedStartsAndTheFirstStartsImmediately() = runTest {
        val gate = DownloadChapterStartGate { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        List(4) { launch { gate.awaitStart(3_000L); starts += testScheduler.currentTime } }.joinAll()
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L), starts)
    }

    @Test
    fun idleQueueStartsImmediatelyInsteadOfAddingAnotherFullDelay() = runTest {
        val gate = DownloadChapterStartGate { testScheduler.currentTime }
        gate.awaitStart(1_000L)
        advanceTimeBy(5_000L)
        gate.awaitStart(1_000L)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun cancellationDuringDelayDoesNotRunAChapterOrReserveAnExtraStart() = runTest {
        val gate = DownloadChapterStartGate { testScheduler.currentTime }
        gate.awaitStart(1_000L)
        var cancelledStarted = false
        val cancelled = launch { gate.awaitStart(1_000L); cancelledStarted = true }
        runCurrent()
        advanceTimeBy(500L)
        cancelled.cancelAndJoin()
        assertFalse(cancelledStarted)
        gate.awaitStart(1_000L)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun cancellingAChapterWaitingForTheClockReleasesItsConcurrencySlot() = runTest {
        val gate = DownloadChapterStartGate { testScheduler.currentTime }
        val queue = DownloadChapterQueue { 1 }
        gate.awaitStart(1_000L)
        val cancelled = launch { queue.acquire().use { gate.awaitStart(1_000L) } }
        runCurrent()
        var nextStarted = false
        val next = launch { queue.acquire().use { gate.awaitStart(1_000L); nextStarted = true } }
        runCurrent()
        advanceTimeBy(500L)
        cancelled.cancelAndJoin()
        next.join()
        assertTrue(nextStarted)
        assertEquals(1_000L, testScheduler.currentTime)
    }
}
