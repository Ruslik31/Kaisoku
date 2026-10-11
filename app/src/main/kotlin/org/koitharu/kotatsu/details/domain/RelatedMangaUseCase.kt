package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koitharu.kotatsu.core.model.isNsfw

class RelatedMangaUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
    private val engine: org.koitharu.kotatsu.suggestions.local.RecommendationEngine,
) {

    fun observe(seed: Manga): kotlinx.coroutines.flow.Flow<org.koitharu.kotatsu.suggestions.local.RecommendationSnapshot> =
        engine.settings.observe(*org.koitharu.kotatsu.suggestions.local.RecommendationEngine.KEYS).let { settingsFlow ->
            kotlinx.coroutines.flow.flow {
                emit(engine.settings.useLegacySuggestions to (engine.settings.useLegacySuggestions && engine.settings.isNsfwContentDisabled))
                settingsFlow.collect { emit(engine.settings.useLegacySuggestions to (engine.settings.useLegacySuggestions && engine.settings.isNsfwContentDisabled)) }
            }
        }.distinctUntilChanged().flatMapLatest { (legacy, _) ->
            if (legacy) kotlinx.coroutines.flow.flow {
                val values = invoke(seed).orEmpty().filterNot { engine.settings.isNsfwContentDisabled && it.isNsfw() }
                emit(org.koitharu.kotatsu.suggestions.local.RecommendationSnapshot(values.map {
                    org.koitharu.kotatsu.suggestions.local.Recommendation(org.koitharu.kotatsu.suggestions.local.RecommendationTarget.SourceTitle(it))
                }))
            } else engine.observe(org.koitharu.kotatsu.suggestions.local.RecommendationRequest.Details(seed))
        }

    suspend fun refresh(seed: Manga) = engine.refresh(org.koitharu.kotatsu.suggestions.local.RecommendationRequest.Details(seed))

	suspend operator fun invoke(seed: Manga) = runCatchingCancellable {
		mangaRepositoryFactory.create(seed.source).getRelated(seed)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()
}
