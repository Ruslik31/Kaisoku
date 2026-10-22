package org.koitharu.kotatsu.search.domain

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

internal fun filterSuggestedSources(
    sources: Collection<MangaParserSource>,
    query: String,
    limit: Int,
    skipNsfw: Boolean,
    contentPolicy: SourceContentPolicy? = null,
): List<MangaSource> {
    if (query.length < 3) return emptyList()
    val matches = sources.filter { source ->
        (contentPolicy?.allows(source) ?: (!skipNsfw || !source.isNsfw())) && source.title.contains(query, ignoreCase = true)
    }
    return if (limit == 0) matches else matches.take(limit)
}
