package org.koitharu.kotatsu.search.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.NsfwSourceOverrides
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class SourceSuggestionsTest {
    private val safe = MangaParserSource.MANGA_OVH
    private val adult = MangaParserSource.EXHENTAI

    @After
    fun resetOverrides() {
        NsfwSourceOverrides.replaceAll(emptyMap())
    }

    @Test
    fun aManuallyMarkedSourceIsHiddenEvenWhenItsParserTypeIsSafe() {
        NsfwSourceOverrides.replaceAll(mapOf(safe.name to true))
        assertTrue(filterSuggestedSources(listOf(safe), safe.title, 6, true).isEmpty())
        assertEquals(listOf(safe), filterSuggestedSources(listOf(safe), safe.title, 6, false))
    }

    @Test
    fun explicitSafeOverridesMatchTheSourceCatalogPolicy() {
        assertTrue(filterSuggestedSources(listOf(adult), adult.title, 6, true).isEmpty())
        NsfwSourceOverrides.replaceAll(mapOf(adult.name to false))
        assertEquals(listOf(adult), filterSuggestedSources(listOf(adult), adult.title, 6, true))
    }

    @Test
    fun filteringKeepsCaseInsensitiveMatchingAndExistingQueryLimits() {
        assertEquals(listOf(safe), filterSuggestedSources(listOf(safe, safe), safe.title.lowercase(), 1, false))
        assertEquals(listOf(safe, safe), filterSuggestedSources(listOf(safe, safe), safe.title, 0, false))
        assertTrue(filterSuggestedSources(listOf(safe), "ma", 6, false).isEmpty())
    }
}
