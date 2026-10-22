package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.normalizeListFilters
import org.koitharu.kotatsu.parsers.model.MangaState

class FavoritesFilterPanelTest {
    @Test fun downloadChoicesExcludeTheirOppositeWhilePreservingOtherFilters() {
        val combined = normalizeListFilters(listOf(ListFilterOption.Downloaded, ListFilterOption.Macro.NEW_CHAPTERS,
            ListFilterOption.NOT_DOWNLOADED))
        assertEquals(setOf(ListFilterOption.NOT_DOWNLOADED, ListFilterOption.Macro.NEW_CHAPTERS), combined)
        assertEquals(setOf(ListFilterOption.Downloaded), normalizeListFilters(listOf(ListFilterOption.NOT_DOWNLOADED, ListFilterOption.Downloaded)))
    }
    @Test fun publicationStatesAreExclusiveAndTheEmptyStateChoiceClearsOnlyThatGroup() {
        val states = MangaState.entries.take(2)
        val result = normalizeListFilters(listOf(ListFilterOption.Macro.COMPLETED,
            ListFilterOption.State(states[0]), ListFilterOption.State(states[1])))
        assertEquals(setOf(ListFilterOption.Macro.COMPLETED, ListFilterOption.State(states[1])), result)
        assertEquals(setOf(ListFilterOption.Macro.COMPLETED), normalizeListFilters(result + ListFilterOption.State(null)))
    }
    @Test fun applyingAnUnchangedSnapshotAndClearingAreStable() {
        val chosen = listOf(ListFilterOption.Downloaded, ListFilterOption.Macro.COMPLETED, ListFilterOption.Downloaded)
        val first = normalizeListFilters(chosen)
        assertEquals(first, normalizeListFilters(first))
        assertEquals(2, first.size)
        assertTrue(normalizeListFilters(emptyList()).isEmpty())
    }
}
