package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CategorySortOrderTest {
    private val items = listOf(
        CategorySortKey(1, "Zulu", 1, Long.MAX_VALUE, 0),
        CategorySortKey(2, "alpha", 3, Long.MIN_VALUE, Int.MAX_VALUE),
        CategorySortKey(3, "Beta", 2, 0, 0),
    )
    @Test fun defaultManualOrderDoesNotChangeWhenDirectionSwitches() {
        for (ascending in listOf(false, true)) assertEquals(listOf(1L, 3L, 2L), order(CategorySortOrder.MANUAL, ascending))
    }
    @Test fun namesUseLocaleCollationAndReverseCorrectly() {
        assertEquals(listOf(2L, 3L, 1L), order(CategorySortOrder.NAME))
        assertEquals(listOf(1L, 3L, 2L), order(CategorySortOrder.NAME, false))
    }
    @Test fun countsUseStableStoredOrderForTiesIncludingEmptyCategories() {
        assertEquals(listOf(1L, 3L, 2L), order(CategorySortOrder.COUNT))
        assertEquals(listOf(2L, 1L, 3L), order(CategorySortOrder.COUNT, false))
    }
    @Test fun extremeCreationDatesDoNotOverflow() {
        assertEquals(listOf(2L, 3L, 1L), order(CategorySortOrder.CREATED))
        assertEquals(listOf(1L, 3L, 2L), order(CategorySortOrder.CREATED, false))
    }
    @Test fun mixedScriptsRemainDeterministicAndNeitherLoseNorMergeCategories() {
        val values = listOf("中文", "قراءة", "日本語", "Reading", "Reading").mapIndexed { i, title ->
            CategorySortKey(i.toLong(), title, i, 0, 0)
        }
        val first = sortFavoriteCategories(values, CategorySortOrder.NAME, true, Locale.ENGLISH)
        assertEquals(first, sortFavoriteCategories(values.reversed(), CategorySortOrder.NAME, true, Locale.ENGLISH))
        assertEquals(values.map { it.id }.toSet(), first.map { it.id }.toSet())
        assertTrue(first.indexOfFirst { it.id == 3L } < first.indexOfFirst { it.id == 4L })
    }
    private fun order(sort: CategorySortOrder, ascending: Boolean = true) =
        sortFavoriteCategories(items, sort, ascending, Locale.ENGLISH).map { it.id }
}
