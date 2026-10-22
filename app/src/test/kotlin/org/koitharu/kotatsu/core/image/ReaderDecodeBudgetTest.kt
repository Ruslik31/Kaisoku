package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ReaderDecodeBudgetTest {

    @Test
    fun rejectsInvalidAndOversizedDimensionsBeforeAnyPixelAllocation() {
        listOf(0 to 10, -1 to 10, 10 to 0, Int.MAX_VALUE to Int.MAX_VALUE, 32_769 to 1).forEach { (w, h) ->
            assertThrows(IOException::class.java) { ReaderDecodeLimits.workingBytes(w, h, false) }
        }
        assertThrows(IOException::class.java) { ReaderDecodeLimits.workingBytes(4096, 4096, false) }
        assertEquals(48L * 1024 * 1024, ReaderDecodeLimits.workingBytes(2048, 2048, false))
        assertThrows(IOException::class.java) { ReaderDecodeLimits.workingBytes(2048, 2048, true) }
    }

    @Test
    fun liveReservationsShareAProcessBudgetAndReleaseExactlyOnce() {
        val budget = ReaderDecodeBudget(100)
        val first = budget.reserve(60)
        assertThrows(IOException::class.java) { budget.reserve(41) }
        assertThrows(IOException::class.java) { budget.reserve(Long.MAX_VALUE) }
        first.close()
        first.close()
        budget.reserve(100).use {
            assertThrows(IOException::class.java) { budget.reserve(1) }
        }
        budget.reserve(100).close()
        assertThrows(IOException::class.java) { budget.reserve(0) }
        assertThrows(IOException::class.java) { budget.reserve(-1) }
    }

    @Test
    fun sampledDimensionsPreserveNonDivisibleAndLargeCoordinates() {
        assertEquals(5, ReaderDecodeLimits.sampledDimension(9, 2))
        assertEquals(1, ReaderDecodeLimits.sampledDimension(9, 16))
        assertEquals(1_073_741_824, ReaderDecodeLimits.sampledDimension(Int.MAX_VALUE, 2))
        assertThrows(IllegalArgumentException::class.java) { ReaderDecodeLimits.sampledDimension(10, 0) }
    }

    @Test
    fun alternateNativeAllocationsAreSerializedAndReleasePermitOnFailure() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val workers = List(8) {
            thread {
                ready.countDown()
                start.await()
                ReaderDecodeResources.withAllocationPermit {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    Thread.yield()
                    active.decrementAndGet()
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, maximum.get())
        assertThrows(IOException::class.java) {
            ReaderDecodeResources.withAllocationPermit { throw IOException("fixture") }
        }
        assertEquals(7, ReaderDecodeResources.withAllocationPermit { 7 })
    }

    @Test
    fun interruptedWaitDoesNotAllocateOrConsumePermit() {
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                ReaderDecodeResources.withAllocationPermit { error("Must not allocate") }
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(9, ReaderDecodeResources.withAllocationPermit { 9 })
    }
}
