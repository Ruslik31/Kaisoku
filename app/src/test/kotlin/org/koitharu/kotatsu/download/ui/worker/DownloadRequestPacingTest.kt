package org.koitharu.kotatsu.download.ui.worker

import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.DownloadControlValues
import org.koitharu.kotatsu.parsers.model.MangaSource

class DownloadRequestPacingTest {

    @Test
    fun globalIntervalSpacesImageRequestsFromSeveralWorkersForTheSameSource() = runTest {
        val slots = MutableObjectLongMap<MangaSource>()
        val starts = mutableListOf<Long>()
        val interval = DownloadControlValues.effectiveRequestInterval(3_000L, false)
        List(4) {
            launch {
                awaitDownloadRequestSlot(slots, firstSource, interval) { testScheduler.currentTime }
                starts += testScheduler.currentTime
            }
        }.joinAll()
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L), starts)
    }

    @Test
    fun differentSourcesKeepIndependentImageRequestSlots() = runTest {
        val slots = MutableObjectLongMap<MangaSource>()
        val starts = mutableListOf<Pair<String, Long>>()
        listOf(firstSource, secondSource, firstSource, secondSource).map { source ->
            launch {
                awaitDownloadRequestSlot(slots, source, 5_000L) { testScheduler.currentTime }
                starts += source.name to testScheduler.currentTime
            }
        }.joinAll()
        assertEquals(listOf("first" to 0L, "second" to 0L, "first" to 5_000L, "second" to 5_000L), starts)
    }

    @Test
    fun defaultNativeSlowdownKeepsItsExisting1600MillisecondReservations() = runTest {
        val slots = MutableObjectLongMap<MangaSource>()
        val starts = mutableListOf<Long>()
        val interval = DownloadControlValues.effectiveRequestInterval(0L, true)
        List(3) {
            launch {
                awaitDownloadRequestSlot(slots, firstSource, interval) { testScheduler.currentTime }
                starts += testScheduler.currentTime
            }
        }.joinAll()
        assertEquals(listOf(0L, 1_600L, 3_200L), starts)
    }

    @Test
    fun cancellingWhilePacingPreventsTheImageRequestImmediately() = runTest {
        val slots = MutableObjectLongMap<MangaSource>()
        awaitDownloadRequestSlot(slots, firstSource, 5_000L) { testScheduler.currentTime }
        var requestStarted = false
        val waiting = launch {
            awaitDownloadRequestSlot(slots, firstSource, 5_000L) { testScheduler.currentTime }
            requestStarted = true
        }
        runCurrent()
        advanceTimeBy(500L)
        waiting.cancelAndJoin()
        assertFalse(requestStarted)
        assertEquals(500L, testScheduler.currentTime)
    }

    private val firstSource = object : MangaSource { override val name = "first" }
    private val secondSource = object : MangaSource { override val name = "second" }
}
