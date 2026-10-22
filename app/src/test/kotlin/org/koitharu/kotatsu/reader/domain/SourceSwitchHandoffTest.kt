package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceSwitchHandoffTest {
    @Test
    fun migrationWaitsForOldReaderAndItsQueuedHistoryWrites() = runTest {
        val events = mutableListOf<String>()
        val releaseOldSave = CompletableDeferred<Unit>()
        val reader = launch {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                withContext(NonCancellable) {
                    releaseOldSave.await()
                    events += "old-save"
                }
            }
        }
        runCurrent()
        val transfer = async {
            sourceSwitchHandoff(listOf(reader), { events += "last-save" }) {
                events += "migration"
                "replacement"
            }
        }
        runCurrent()
        assertTrue(reader.isCancelled)
        assertFalse(transfer.isCompleted)
        assertTrue(events.isEmpty())
        releaseOldSave.complete(Unit)
        assertEquals("replacement", transfer.await())
        assertEquals(listOf("old-save", "last-save", "migration"), events)
    }

    @Test
    fun failedFinalSaveNeverStartsMigration() = runTest {
        var migrated = false
        try {
            sourceSwitchHandoff(emptyList(), { throw IOException("save failed") }) { migrated = true }
            error("Expected save failure")
        } catch (_: IOException) {
            assertFalse(migrated)
        }
    }

    @Test
    fun cancellationWhileDrainingNeverStartsMigration() = runTest {
        val pending = CompletableDeferred<Unit>()
        var migrated = false
        val reader = launch {
            try { CompletableDeferred<Unit>().await() } finally { withContext(NonCancellable) { pending.await() } }
        }
        runCurrent()
        val transfer = launch { sourceSwitchHandoff(listOf(reader), {}) { migrated = true } }
        runCurrent()
        transfer.cancel()
        pending.complete(Unit)
        transfer.join()
        assertFalse(migrated)
    }
}
