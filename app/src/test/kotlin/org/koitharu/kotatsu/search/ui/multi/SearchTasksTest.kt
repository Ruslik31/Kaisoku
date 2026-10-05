package org.koitharu.kotatsu.search.ui.multi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SearchTasksTest {
    @Test fun replacingAQueryCancelsEnabledSearchAndItsWaitingContinuation() = runTest {
        var enabledCancelled = false
        var disabledStarted = false
        val enabled = launch {
            try { awaitCancellation() } finally { enabledCancelled = true }
        }
        val continuation = launch { enabled.join(); disabledStarted = true }
        runCurrent()
        cancelSearchTasks(listOf(enabled, continuation))
        assertTrue(enabledCancelled)
        assertTrue(enabled.isCancelled)
        assertTrue(continuation.isCancelled)
        assertFalse(disabledStarted)
    }

    @Test fun remoteResultsAreDisplayedWhileLocalDiskScanIsStillRunning() = runTest {
        val localGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch { runSearchTasks(listOf({ localGate.await(); events += "local" }), { events += "remote" }) }
        runCurrent()
        assertEquals(listOf("remote"), events)
        assertTrue(job.isActive)
        localGate.complete(Unit)
        job.join()
        assertEquals(listOf("remote", "local"), events)
    }

    @Test fun cancellingTheSearchCancelsLocalAndRemoteWork() = runTest {
        var localCancelled = false
        var remoteCancelled = false
        val job = launch {
            runSearchTasks(listOf({ try { awaitCancellation() } finally { localCancelled = true } }), {
                try { awaitCancellation() } finally { remoteCancelled = true }
            })
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(localCancelled)
        assertTrue(remoteCancelled)
    }

    @Test fun handledLocalFailureDoesNotStrandRemoteResults() = runTest {
        val events = mutableListOf<String>()
        runSearchTasks(listOf({ runCatching { error("Disk unavailable") }; events += "local error" }), {
            events += "remote"
        })
        assertTrue(events.containsAll(listOf("local error", "remote")))
    }
}
