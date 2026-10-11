package org.koitharu.kotatsu.suggestions.domain

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTagsList
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.suggestions.data.SuggestionEntity
import org.koitharu.kotatsu.suggestions.data.SuggestionWithManga
import javax.inject.Inject
import org.koitharu.kotatsu.core.model.isNsfw
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.suggestions.local.RecommendationEngine
import org.koitharu.kotatsu.suggestions.local.RecommendationRequest
import org.koitharu.kotatsu.suggestions.local.Recommendation
import org.koitharu.kotatsu.suggestions.local.RecommendationTarget
import org.koitharu.kotatsu.suggestions.local.RecommendationSnapshot

class SuggestionRepository @Inject constructor(
    private val db: MangaDatabase,
    private val engine: RecommendationEngine,
    private val settings: AppSettings,
) {

    fun observeDisplay(limit: Int = 0, filterOptions: Set<ListFilterOption> = emptySet()): Flow<RecommendationSnapshot> =
        settings.observe(*RecommendationEngine.KEYS).onStart { emit(null) }.flatMapLatest {
            if (settings.useLegacySuggestions) observeAll(limit, filterOptions).map { list ->
                RecommendationSnapshot(list.map { Recommendation(RecommendationTarget.SourceTitle(it)) })
            } else engine.observe(RecommendationRequest.Explore).map { snapshot ->
                snapshot.copy(results = snapshot.results.filter { item ->
                    filterOptions.all { filter -> when (filter) {
                        is ListFilterOption.Tag -> item.manga.tags.any { it.title.equals(filter.tag.title, true) }
                        is ListFilterOption.Source -> item.manga.source.name == filter.mangaSource.name
                        is ListFilterOption.State -> filter.state == null || item.manga.state == filter.state
                        ListFilterOption.Macro.NSFW -> item.manga.isNsfw()
                        is ListFilterOption.Inverted -> filter.option != ListFilterOption.Macro.NSFW || !item.manga.isNsfw()
                        else -> true
                    } }
                }.let { if (limit > 0) it.take(limit) else it })
            }
        }

    fun observeAll(): Flow<List<Manga>> {
        return db.getSuggestionDao().observeAll().mapItems {
            it.toManga()
        }
    }

    fun observeAll(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<Manga>> {
        return db.getSuggestionDao().observeAll(limit, filterOptions).mapItems {
            it.toManga()
        }
    }

    suspend fun getRandomList(limit: Int): List<Manga> {
        return db.getSuggestionDao().getRandom(limit).map {
            it.toManga()
        }
    }

    suspend fun clear() {
        db.getSuggestionDao().deleteAll()
    }

    suspend fun isEmpty(): Boolean {
        return db.getSuggestionDao().count() == 0
    }

    suspend fun getTopTags(limit: Int): List<MangaTag> {
        if (!settings.useLegacySuggestions) return engine.cachedExplore().flatMap { it.manga.tags }.groupBy { it.title }
            .entries.sortedByDescending { it.value.size }.take(limit).map { it.value.first() }
        return db.getSuggestionDao().getTopTags(limit)
            .toMangaTagsList()
    }

    suspend fun getTopSources(limit: Int): List<MangaSource> {
        if (!settings.useLegacySuggestions) return engine.cachedExplore().map { it.manga.source }.groupBy { it.name }
            .entries.sortedByDescending { it.value.size }.take(limit).map { it.value.first() }
        return db.getSuggestionDao().getTopSources(limit)
            .toMangaSources()
    }

    suspend fun replace(suggestions: Iterable<MangaSuggestion>) {
        db.withTransaction {
            db.getSuggestionDao().deleteAll()
            suggestions.forEach { (manga, relevance) ->
                val tags = manga.tags.toEntities()
                db.getTagsDao().upsert(tags)
                db.getMangaDao().upsert(manga.toEntity(), tags)
                db.getSuggestionDao().upsert(
                    SuggestionEntity(
                        mangaId = manga.id,
                        relevance = relevance,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun SuggestionWithManga.toManga() = manga.toManga(emptySet(), null)
}
