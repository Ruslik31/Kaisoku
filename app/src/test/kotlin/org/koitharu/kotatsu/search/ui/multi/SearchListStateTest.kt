package org.koitharu.kotatsu.search.ui.multi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.LoadingState
import java.io.IOException

class SearchListStateTest {
    @Test
    fun completedEmptySearchDoesNotNeedAnObservedLoadingTransition() = runTest {
        val loading = MutableStateFlow(false)
        // An empty/fast search may complete before the eagerly launched list collector runs.
        loading.value = true
        loading.value = false
        val list = withTimeout(1_000) {
            observeSearchResults(
                MutableStateFlow(emptyList()), loading, MutableStateFlow(false), MutableStateFlow(true),
            ).first()
        }
        assertTrue(list.single() is EmptyState)
    }

    @Test
    fun completedSearchRetainsErrorsWithoutWaitingForAnotherSearch() = runTest {
        val error = SearchResultsListModel(0, LocalMangaSource, null, null, emptyList(), IOException("Offline"))
        val list = withTimeout(1_000) {
            observeSearchResults(
                MutableStateFlow(listOf(error)), MutableStateFlow(false), MutableStateFlow(true),
                MutableStateFlow(false),
            ).first()
        }
        assertEquals(listOf(error), list)
    }

    @Test
    fun retryCanLoadAndCompleteWithoutLeavingAStaleSpinner() = runTest {
        val loading = MutableStateFlow(false)
        val list = observeSearchResults(
            MutableStateFlow(emptyList()), loading, MutableStateFlow(false), MutableStateFlow(true),
        )
        assertTrue(withTimeout(1_000) { list.first().single() } is EmptyState)
        loading.value = true
        assertSame(LoadingState, withTimeout(1_000) { list.first().single() })
        loading.value = false
        assertTrue(withTimeout(1_000) { list.first().single() } is EmptyState)
    }
}
