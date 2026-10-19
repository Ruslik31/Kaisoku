package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.suggestions.domain.RecommendationExclusions
import org.koitharu.kotatsu.suggestions.domain.withRecommendationExclusions
import org.koitharu.kotatsu.suggestions.local.Recommendation
import org.koitharu.kotatsu.suggestions.local.RecommendationEngine
import org.koitharu.kotatsu.suggestions.local.RecommendationRequest
import org.koitharu.kotatsu.suggestions.local.RecommendationSnapshot
import org.koitharu.kotatsu.suggestions.local.RecommendationTarget
import javax.inject.Inject

class RelatedMangaUseCase @Inject constructor(
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val engine: RecommendationEngine,
) {
    fun observe(seed: Manga): Flow<RecommendationSnapshot> = engine.settings
        .observe(AppSettings.KEY_SUGGESTIONS_LEGACY).onStart { emit(null) }
        .map { engine.settings.useLegacySuggestions }.distinctUntilChanged().flatMapLatest { legacy ->
            if (legacy) {
                flow { emit(invoke(seed).orEmpty()) }.withRecommendationExclusions(
                    engine.settings.observe(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, AppSettings.KEY_DISABLE_NSFW,
                        AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW, AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED,
                    AppSettings.KEY_SOURCE_CONTENT_CLASSES).onStart { emit(null) }.map {
                        RecommendationExclusions(engine.settings.suggestionsTagsBlacklist,
                            engine.settings.isNsfwContentDisabled || engine.settings.isSuggestionsExcludeNsfw, engine.settings.sourceContentPolicy)
                    },
                ).map { values ->
                    RecommendationSnapshot(values.map { Recommendation(RecommendationTarget.SourceTitle(it)) })
                }
            } else engine.observe(RecommendationRequest.Details(seed))
        }

    suspend fun refresh(seed: Manga) = engine.refresh(RecommendationRequest.Details(seed), force = true)

    suspend operator fun invoke(seed: Manga) = runCatchingCancellable {
        mangaRepositoryFactory.create(seed.source).getRelated(seed)
    }.onFailure { it.printStackTraceDebug() }.getOrNull()
}
