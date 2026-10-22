package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationFlowTest {
    @Test fun hiddenScreensStopTasteQueriesAndKeepCachedCardsForRecreation() = runTest {
        val library = MutableStateFlow(1)
        var queries = 0
        var subscriptions = 0
        var cancellations = 0
        val cards = flow {
            subscriptions++
            try {
                library.collect { value -> queries++; emit(value) }
            } finally {
                cancellations++
            }
        }.shareRecommendations(backgroundScope, 0)

        runCurrent()
        assertEquals(0, queries)
        val screen = launch { cards.collect() }
        runCurrent()
        assertEquals(1, queries)
        assertEquals(1, cards.value)

        screen.cancelAndJoin()
        runCurrent()
        assertEquals(1, cancellations)
        // Reader page writes must not requery or rank an invisible recommendations screen.
        library.value = 2
        library.value = 3
        runCurrent()
        assertEquals(1, queries)
        assertEquals(1, cards.value)

        val recreated = launch { cards.collect() }
        runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(2, queries)
        assertEquals(3, cards.value)
        recreated.cancelAndJoin()
    }

    @Test fun lastVisibleSubscriberCancelsSharedDiscovery() = runTest {
        val jobs = RecommendationJobs(backgroundScope)
        var operations = 0
        var released = false
        val cards = flow {
            emit("cached")
            jobs.run("details", "current") {
                operations++
                try { awaitCancellation() } finally { released = true }
            }
        }.shareRecommendations(backgroundScope, "loading")
        val screen = launch { cards.collect() }
        val expanded = launch { cards.collect() }
        runCurrent()
        assertEquals(1, operations)
        screen.cancelAndJoin()
        runCurrent()
        assertEquals(false, released)
        expanded.cancelAndJoin()
        runCurrent()
        assertTrue(released)
        assertEquals("cached", cards.value)
    }
}
