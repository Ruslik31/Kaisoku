package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

/** Discovery never reads the repository's active filters or advances its browse cursor. */
internal class MihonDiscovery(private val source: CatalogueSource) {
    suspend fun get(order: SortOrder, filter: MangaListFilter): MangasPage {
        val query = filter.query?.trim().orEmpty()
        return when {
            query.isNotEmpty() -> source.getSearchManga(1, query, source.getFilterList())
            order == SortOrder.UPDATED && source.supportsLatest -> source.getLatestUpdates(1)
            else -> source.getPopularManga(1)
        }
    }
}
