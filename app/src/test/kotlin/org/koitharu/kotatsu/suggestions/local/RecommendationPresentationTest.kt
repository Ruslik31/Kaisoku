package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.list.ui.model.ErrorState
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.RecommendationWarning
import org.koitharu.kotatsu.parsers.model.Manga

class RecommendationPresentationTest {
    private val cards = (1L..3L).map { id ->
        MangaGridModel(Manga(id, "Title $id", emptySet(), "/$id", "https://fixture.invalid/$id", -1f, null,
            null, emptySet(), null, emptySet(), source = TestMangaSource), null, 0, null, false, false)
    }
    private val diagnostics = "DESUME: Unable to resolve host\n" +
        "lnreader:RLIB: LNReader JS Error\n" + "    at <anonymous> (<lnreader-plugin>)\n".repeat(1000)

    @Test fun partialFailureKeepsEveryCardAndUsesAnInlineWarning() {
        val rows = recommendationContent(cards, diagnostics)
        assertEquals(cards, rows.drop(1))
        assertTrue(rows.none { it is ErrorState })
        val warning = rows.first() as RecommendationWarning
        assertEquals(diagnostics, warning.details)
    }

    @Test fun totalFailureRetainsNormalEmptyPlaceholderAndCopyableDetails() {
        val empty = org.koitharu.kotatsu.list.ui.model.EmptyState(
            org.koitharu.kotatsu.R.drawable.ic_empty_common, org.koitharu.kotatsu.R.string.nothing_found, 0, 0,
        )
        val rows = recommendationContent(listOf(empty), diagnostics)
        val warning = rows.first() as RecommendationWarning
        assertEquals(empty, rows.last())
        assertTrue(rows.none { it is ErrorState })
        assertEquals(diagnostics, warning.details)
    }

    @Test fun recoveryRemovesWarningWithoutReplacingOrReorderingCards() {
        val failed = recommendationContent(cards, diagnostics)
        val recovered = recommendationContent(cards, null)
        assertEquals(failed.filterIsInstance<MangaGridModel>(), recovered)
        assertTrue(recovered.none { it is RecommendationWarning })
        assertSame(cards.first().manga, (recovered.first() as MangaGridModel).manga)
    }

    @Test fun emptyAndBlankDiagnosticsDoNotCreateWarnings() {
        assertEquals(cards, recommendationContent(cards, "\n  "))
        assertTrue(recommendationContent(emptyList(), null).isEmpty())
    }
}
