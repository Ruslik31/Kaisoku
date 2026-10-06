package org.koitharu.kotatsu.scrobbling.common.data

import org.junit.Assert.*
import org.junit.Test

class TrackerProgressStoreTest {
    private fun progress(account: Long = 1, target: Long = 10, chapter: Int = 7, revision: Long = 1) =
        PendingTrackerProgress(account, -88, target, target.toInt(), chapter, revision)

    @Test fun pendingProgressSurvivesRestartAndCoalescesUpward() {
        val prefs = MemoryPreferences(); val first = TrackerProgressStore(prefs)
        assertTrue(first.enqueue(progress(chapter = 9)))
        assertFalse(first.enqueue(progress(chapter = 3)))
        assertTrue(first.enqueue(progress(chapter = 11)))
        assertEquals(11, TrackerProgressStore(prefs).read(1, -88)?.chapter)
    }

    @Test fun accountIsolationRetainsIndependentProgressForTheSameTitle() {
        val store = TrackerProgressStore(MemoryPreferences())
        store.enqueue(progress(account = 1, chapter = 9)); store.enqueue(progress(account = 2, chapter = 3))
        assertEquals(9, store.read(1, -88)?.chapter); assertEquals(3, store.read(2, -88)?.chapter)
        assertEquals(1, store.pendingCount(1)); assertEquals(listOf(2L), store.all(2).map { it.accountId })
    }

    @Test fun anUpdateQueuedDuringAnInFlightRequestIsNotLostByItsAcknowledgement() {
        val store = TrackerProgressStore(MemoryPreferences())
        store.enqueue(progress()); val sending = store.read(1, -88)!!
        store.enqueue(progress(chapter = 12)); store.acknowledge(sending)
        assertEquals(12, store.read(1, -88)?.chapter)
        store.acknowledge(store.read(1, -88)!!); assertNull(store.read(1, -88))
    }

    @Test fun relinkingAndLateFailuresDoNotDeleteProgressForTheNewMatch() {
        val store = TrackerProgressStore(MemoryPreferences())
        store.enqueue(progress(chapter = 100)); val old = store.read(1, -88)!!
        store.invalidate(1, -88, manual = true)
        store.enqueue(progress(target = 20, chapter = 2)); store.fail(old, "late error")
        assertEquals(20L, store.read(1, -88)?.targetId); assertEquals(2, store.read(1, -88)?.chapter)
        assertNull(store.error(1)); assertTrue(store.wasManuallyMatched(1, -88))
    }

    @Test fun logoutClearsPendingWorkButKeepsManualMatchingPreference() {
        val store = TrackerProgressStore(MemoryPreferences())
        store.invalidate(1, -88, manual = true); store.enqueue(progress()); store.clearPending()
        assertEquals(0, store.pendingCount(1)); assertTrue(store.wasManuallyMatched(1, -88))
    }

    @Test fun alreadyAcknowledgedProgressDoesNotRepeatedlyRescheduleReadingCallbacks() {
        val store = TrackerProgressStore(MemoryPreferences())
        store.enqueue(progress()); store.acknowledge(store.read(1, -88)!!)
        assertFalse(store.enqueue(progress(chapter = 3))); assertFalse(store.enqueue(progress()))
        assertTrue(store.enqueue(progress(chapter = 9)))
    }

    @Test fun fetchingRemoteProgressPreventsAutomaticDecreases() {
        assertNull(nextTrackerProgress(12, 40)); assertNull(nextTrackerProgress(40, 40))
        assertEquals(41, nextTrackerProgress(41, 40)); assertNull(nextTrackerProgress(-1, 0))
    }

    @Test fun rejectingAnObsoleteMatchKeepsNewlyQueuedProgress() {
        val store = TrackerProgressStore(MemoryPreferences())
        store.enqueue(progress()); val old = store.read(1, -88)!!
        store.enqueue(progress(target = 20, chapter = 2))
        store.discard(old)
        assertEquals(20L, store.read(1, -88)?.targetId)
        store.discard(store.read(1, -88)!!)
        assertNull(store.read(1, -88))
    }
}
