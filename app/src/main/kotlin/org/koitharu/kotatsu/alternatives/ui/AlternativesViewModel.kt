package org.koitharu.kotatsu.alternatives.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativesUseCase
import org.koitharu.kotatsu.alternatives.domain.AlternativesSearchOptions
import org.koitharu.kotatsu.alternatives.domain.AlternativeSortOrder
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.append
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrDefault
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class AlternativesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaListMapper: MangaListMapper,
) : BaseViewModel() {

	val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	private var includeDisabledSources = MutableStateFlow(false)
	private val results = MutableStateFlow<List<MangaAlternativeModel>>(emptyList())
	val options = MutableStateFlow(AlternativesSearchOptions(query = manga.title))

	private var migrationJob: Job? = null
	private var searchJob: Job? = null

	private val mangaDetails = suspendLazy {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}

	val onMigrated = MutableEventFlow<Manga>()

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading,
		includeDisabledSources,
		options,
	) { list, loading, includeDisabled, searchOptions ->
		val visible = list
			.filter { !searchOptions.hideNoChapters || it.chaptersCount > 0 }
			.let { items ->
				when (searchOptions.sortOrder) {
					AlternativeSortOrder.BEST_MATCH -> items.sortedByDescending {
						bestMatchScore(searchOptions.query, it.manga.title)
					}
					AlternativeSortOrder.MOST_CHAPTERS -> items.sortedByDescending(MangaAlternativeModel::chaptersCount)
					AlternativeSortOrder.CLOSEST_CHAPTER_COUNT -> items.sortedBy { abs(it.chaptersDiff) }
					AlternativeSortOrder.SOURCE_PRIORITY -> items
				}
			}
		when {
			visible.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)

			loading -> visible + LoadingFooter()
			includeDisabled -> visible
			else -> visible + ButtonFooter(R.string.search_disabled_sources)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		doSearch(throughDisabledSources = false)
	}

	fun retry() {
		searchJob?.cancel()
		results.value = emptyList()
		includeDisabledSources.value = false
		doSearch(throughDisabledSources = false)
	}

	fun setQuery(query: String) = updateSearchOptions { copy(query = query.trim()) }

	fun setSameLanguageOnly(value: Boolean) = updateSearchOptions { copy(sameLanguageOnly = value) }

	fun setSameContentTypeOnly(value: Boolean) = updateSearchOptions { copy(sameContentTypeOnly = value) }

	fun setHideNoChapters(value: Boolean) {
		options.update { it.copy(hideNoChapters = value) }
	}

	fun setSortOrder(value: AlternativeSortOrder) {
		options.update { it.copy(sortOrder = value) }
	}

	fun resetOptions() {
		options.value = AlternativesSearchOptions(query = manga.title)
		restartSearch()
	}

	fun hasCustomOptions(): Boolean = options.value != AlternativesSearchOptions(query = manga.title)

	fun continueSearch() {
		if (includeDisabledSources.value) {
			return
		}
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			includeDisabledSources.value = true
			prevJob?.join()
			doSearch(throughDisabledSources = true)
		}
	}

	fun migrate(target: Manga) {
		if (migrationJob?.isActive == true) {
			return
		}
		migrationJob = launchLoadingJob(Dispatchers.Default) {
			onMigrated.call(migrateUseCase(manga, target))
		}
	}

	private fun doSearch(throughDisabledSources: Boolean) {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			val ref = mangaDetails.getOrDefault(manga)
			val refCount = ref.chaptersCount()
			val searchOptions = options.value
			alternativesUseCase.invoke(
				manga = ref,
				throughDisabledSources = throughDisabledSources,
				query = searchOptions.query,
				sameLanguageOnly = searchOptions.sameLanguageOnly,
				sameContentTypeOnly = searchOptions.sameContentTypeOnly,
			)
				.collect {
					val model = MangaAlternativeModel(
						mangaModel = mangaListMapper.toListModel(it, ListMode.GRID) as MangaGridModel,
						referenceChapters = refCount,
					)
					results.update { current ->
						if (current.any { old -> old.manga.id == model.manga.id && old.manga.source.name == model.manga.source.name }) {
							current
						} else {
							current + model
						}
					}
				}
		}
	}

	private fun updateSearchOptions(block: AlternativesSearchOptions.() -> AlternativesSearchOptions) {
		val updated = options.value.block()
		if (updated == options.value || updated.query.isBlank()) return
		options.value = updated
		restartSearch()
	}

	private fun restartSearch() {
		searchJob?.cancel()
		results.value = emptyList()
		includeDisabledSources.value = false
		doSearch(throughDisabledSources = false)
	}

	private fun bestMatchScore(query: String, title: String): Int {
		val normalizedQuery = query.lowercase().filter(Char::isLetterOrDigit)
		val normalizedTitle = title.lowercase().filter(Char::isLetterOrDigit)
		return when {
			normalizedQuery == normalizedTitle -> 3
			normalizedTitle.startsWith(normalizedQuery) -> 2
			normalizedQuery in normalizedTitle -> 1
			else -> 0
		}
	}
}
