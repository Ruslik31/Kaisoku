package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

class MihonDiscoveryTest {
    @Test fun searchesUseFreshFiltersAndPageOneWithoutChangingBrowseFilters() = runTest {
        val source = Fixture()
        val browse = source.getFilterList()
        (browse.single() as Filter.Text).state = "browse selection"
        val discovery = MihonDiscovery(source)
        discovery.get(SortOrder.RELEVANCE, MangaListFilter(query = " First "))
        discovery.get(SortOrder.RELEVANCE, MangaListFilter(query = "Second"))
        assertEquals(listOf("First:1", "Second:1"), source.searches)
        assertNotSame(source.filters[0], source.filters[1])
        assertNotSame(browse, source.filters[0])
        assertEquals("browse selection", (browse.single() as Filter.Text).state)
    }

    @Test fun popularAndLatestRemainIndependentPageOneRequests() = runTest {
        val source = Fixture()
        val discovery = MihonDiscovery(source)
        repeat(2) { discovery.get(SortOrder.POPULARITY, MangaListFilter()) }
        discovery.get(SortOrder.UPDATED, MangaListFilter())
        assertEquals(listOf(1, 1), source.popular)
        assertEquals(listOf(1), source.latest)
        assertTrue(source.filters.isEmpty())
    }

    @Test fun unavailableLatestFallsBackToPopularAndCancellationIsPreserved() = runTest {
        val source = Fixture(supportsLatest = false)
        val discovery = MihonDiscovery(source)
        discovery.get(SortOrder.UPDATED, MangaListFilter())
        assertEquals(listOf(1), source.popular)
        assertTrue(source.latest.isEmpty())
        source.cancelSearch = true
        assertTrue(runCatching { discovery.get(SortOrder.RELEVANCE, MangaListFilter(query = "cancel")) }
            .exceptionOrNull() is CancellationException)
        assertEquals(1, source.searches.size)
    }

    private class Fixture(override val supportsLatest: Boolean = true) : CatalogueSource {
        override val id = 41L
        override val name = "Discovery fixture"
        override val lang = "en"
        val searches = mutableListOf<String>()
        val filters = mutableListOf<FilterList>()
        val popular = mutableListOf<Int>()
        val latest = mutableListOf<Int>()
        var cancelSearch = false
        override fun getFilterList() = FilterList(object : Filter.Text("filter") {})
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searches.add("$query:$page")
            assertEquals("", (filters.single() as Filter.Text).state)
            this.filters.add(filters)
            if (cancelSearch) throw CancellationException("Fixture cancellation")
            (filters.single() as Filter.Text).state = "source mutated request"
            return MangasPage(emptyList(), false)
        }
        override suspend fun getPopularManga(page: Int): MangasPage {
            popular.add(page)
            return MangasPage(emptyList(), false)
        }
        override suspend fun getLatestUpdates(page: Int): MangasPage {
            latest.add(page)
            return MangasPage(emptyList(), false)
        }
    }
}
