package org.koitharu.kotatsu.suggestions.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.core.model.isNsfwForFiltering
import org.koitharu.kotatsu.core.model.SourceContentPolicy
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.suggestions.local.RecommendationRanker

/** Exact normalized names; excluding one genre never implies excluding a similar genre. */
object RecommendationTagExclusions {
    fun isValid(title: String): Boolean = title.isNotBlank() && ',' !in title &&
        RecommendationRanker.normalize(title).isNotEmpty()

    fun contains(excluded: Set<String>, title: String): Boolean {
        val normalized = RecommendationRanker.normalize(title)
        return normalized.isNotEmpty() && excluded.any { RecommendationRanker.normalize(it) == normalized }
    }

    fun update(excluded: Set<String>, title: String, exclude: Boolean): Set<String> {
        require(isValid(title)) {
            "A recommendation genre must be nonempty and cannot contain a comma"
        }
        val result = excluded.filterNotTo(linkedSetOf()) {
            RecommendationRanker.normalize(it) == RecommendationRanker.normalize(title)
        }
        if (exclude) result.add(excluded.firstOrNull {
            RecommendationRanker.normalize(it) == RecommendationRanker.normalize(title)
        } ?: title.trim())
        return result
    }
}

internal data class RecommendationExclusions(val genres: Set<String>, val excludeAdult: Boolean,
    val sourcePolicy: SourceContentPolicy? = null)

/** Settings changes refilter the existing values without restarting their producer. */
internal fun Flow<List<Manga>>.withRecommendationExclusions(
    exclusions: Flow<RecommendationExclusions>,
    limit: Int = 0,
): Flow<List<Manga>> = combine(this, exclusions) { values, policy ->
    val blacklist = TagsBlacklist(policy.genres)
    values.asSequence()
        .filterNot { it in blacklist || (policy.excludeAdult && it.isNsfwForFiltering(policy.sourcePolicy?.separateClasses == true)) ||
            policy.sourcePolicy?.allows(it.source) == false }
        .let { if (limit > 0) it.take(limit) else it }
        .toList()
}
