package org.koitharu.kotatsu.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticBufferTest {
    @Test fun boundedNumericEventsNeverRetainFreeTextAndDrainOnce() {
        val buffer = DiagnosticBuffer(256)
        repeat(20_000) { buffer.add(it.toLong(), DiagnosticEvent.HTTP, DiagnosticArea.APP, 429, Long.MAX_VALUE, it) }
        val snapshot = buffer.snapshot()
        assertEquals(256, snapshot.lines().size)
        assertTrue(snapshot.length < 32_768)
        assertTrue(snapshot.contains("ms=86400000"))
        assertFalse(snapshot.contains("http:"))
        assertEquals(snapshot, buffer.drain())
        assertTrue(buffer.isEmpty())
        assertEquals("", buffer.drain())
    }
    @Test fun concurrentEventsAndClearAreSafe() {
        val buffer = DiagnosticBuffer(128)
        val threads = List(8) { Thread { repeat(1000) { buffer.add(0, DiagnosticEvent.ERROR, DiagnosticArea.READER, 1, 0, it) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(128, buffer.snapshot().lines().size)
        buffer.clear()
        assertTrue(buffer.isEmpty())
    }
}
