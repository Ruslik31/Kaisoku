package org.koitharu.kotatsu.suggestions.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.suggestions.local.recommendationContent
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.domain.SuggestionsListQuickFilter
import org.koitharu.kotatsu.suggestions.local.shareRecommendations
import javax.inject.Inject
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import kotlinx.coroutines.flow.SharedFlow

@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    repository: SuggestionRepository,
    private val settings: AppSettings,
    private val mangaListMapper: MangaListMapper,
    private val quickFilter: SuggestionsListQuickFilter,
    private val suggestionsScheduler: SuggestionsWorker.Scheduler,
    mangaDataRepository: MangaDataRepository,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener by quickFilter {

    override val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE_SUGGESTIONS) { suggestionsListMode }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.suggestionsListMode)

    override val content = combine(
        quickFilter.appliedOptions.combineWithSettings().flatMapLatest { repository.observeDisplay(0, it) },
        observeListModeWithTriggers(),
    ) { snapshot, mode ->
        val list = snapshot.results.map { it.manga }
        // Read at map time rather than combined in: the query above already re-runs on every filter
        // change, so a second input would render the chips one frame ahead of their results.
        val filters = quickFilter.appliedOptions.value
        val cards = when {
            list.isEmpty() -> listOf(EmptyState(
                icon = R.drawable.ic_empty_common,
                textPrimary = R.string.nothing_found,
                textSecondary = if (filters.isEmpty()) R.string.text_suggestion_holder
                    else R.string.text_empty_holder_secondary_filtered,
                actionStringRes = 0,
            ))
            settings.useLegacySuggestions -> mangaListMapper.toListModelList(list, mode)
            else -> mangaListMapper.toRecommendationModels(snapshot.results, mode)
        }
        listOfNotNull(quickFilter.filterItem(filters)) + recommendationContent(cards, snapshot.error)
    }.catch {
        emit(listOf(it.toErrorState(canRetry = false)))
    }.shareRecommendations(viewModelScope + Dispatchers.Default, listOf(LoadingState))

    override fun onRefresh() = updateSuggestions()

    override fun onRetry() = updateSuggestions()
    val isLegacy get() = settings.useLegacySuggestions
    fun setMedia(value: String) { settings.recommendationMedia = value }

    fun updateSuggestions() {
        launchLoadingJob(Dispatchers.Default) {
            suggestionsScheduler.startNow()
        }
    }
}
