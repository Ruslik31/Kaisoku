package org.koitharu.kotatsu.scrobbling.common.ui.selector

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.NO_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.ui.selector.model.ScrobblerHint
import javax.inject.Inject

@HiltViewModel
class ScrobblingSelectorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val dataRepository: org.koitharu.kotatsu.core.parser.MangaDataRepository,
    private val libraryRepository: org.koitharu.kotatsu.scrobbling.common.data.TrackerLibraryRepository,
) : BaseViewModel() {

    val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

    val availableScrobblers = scrobblers.filter { it.isEnabled }.sortedBy { it.scrobblerService.id }
    private val preferredService = savedStateHandle.get<Int>(AppRouter.KEY_ID)
    private val preferredTarget = savedStateHandle.get<Long>(AppRouter.KEY_TRACKER_TARGET_ID)

    val selectedScrobblerIndex = MutableStateFlow(availableScrobblers.indexOfFirst { it.scrobblerService.id == preferredService }.coerceAtLeast(0))

    private val scrobblerMangaList = MutableStateFlow<List<ScrobblerManga>>(emptyList())
    private val hasNextPage = MutableStateFlow(true)
    private val listError = MutableStateFlow<Throwable?>(null)
    private val resolvedSearchQuery = MutableStateFlow<String?>(null)
    private var loadingJob: Job? = null
    private var doneJob: Job? = null
    private var initJob: Job? = null
    private var searchRevision = 0L
    data class CreateConfirmation(val service: org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService,
        val targetId: Long, val session: org.koitharu.kotatsu.scrobbling.common.data.TrackerSession)
    val creationConfirmation = MutableStateFlow<CreateConfirmation?>(null)

    private val currentScrobbler: Scrobbler
        get() = availableScrobblers[selectedScrobblerIndex.requireValue()]

    val content: StateFlow<List<ListModel>> = combine(
        scrobblerMangaList,
        listError,
        hasNextPage,
    ) { list, error, isHasNextPage ->
        if (list.isNotEmpty()) {
            if (isHasNextPage) {
                list + LoadingFooter()
            } else {
                list
            }
        } else {
            listOf(
                when {
                    error != null -> errorHint(error)
                    isHasNextPage -> LoadingFooter()
                    else -> emptyResultsHint()
                },
            )
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    val selectedItemId = MutableStateFlow(NO_ID)
    val onClose = MutableEventFlow<Unit>()
    private val searchQuery = MutableStateFlow(manga.title)

    val isEmpty: Boolean
        get() = scrobblerMangaList.value.isEmpty()

    init {
        if (availableScrobblers.isNotEmpty()) initialize()
    }

    fun search(query: String) {
        loadingJob?.cancel()
        searchRevision++
        searchQuery.value = query
        resolvedSearchQuery.value = null
        loadList(append = false)
    }

    fun selectItem(id: Long) {
        if (doneJob?.isActive == true) {
            return
        }
        selectedItemId.value = id
    }

    fun loadNextPage() {
        if (scrobblerMangaList.value.isNotEmpty() && hasNextPage.value) {
            loadList(append = true)
        }
    }

    fun retry() {
        loadingJob?.cancel()
        hasNextPage.value = true
        scrobblerMangaList.value = emptyList()
        resolvedSearchQuery.value = null
        loadList(append = false)
    }

    private fun loadList(append: Boolean) {
        if (loadingJob?.isActive == true) {
            return
        }
        loadingJob = launchJob(Dispatchers.Default) {
            listError.value = null
            val tracker = currentScrobbler
            val revision = searchRevision
            val offset = if (append) scrobblerMangaList.value.size else 0
            runCatchingCancellable {
                findManga(tracker, offset)
            }.onSuccess { (query, list) ->
                if (revision != searchRevision || currentScrobbler !== tracker) return@onSuccess
                resolvedSearchQuery.value = query
                val newList = (if (append) {
                    scrobblerMangaList.value + list
                } else {
                    list
                }).distinctBy { x -> x.id }
                val changed = newList != scrobblerMangaList.value
                scrobblerMangaList.value = newList
                hasNextPage.value = changed && newList.isNotEmpty()
            }.onFailure { error ->
                error.printStackTraceDebug()
                hasNextPage.value = false
                listError.value = error
            }
        }
    }

    fun onDoneClick() = linkSelected(allowCreate = false)

    fun cancelCreate() { creationConfirmation.value = null }

    fun confirmCreate() {
        val pending = creationConfirmation.value ?: return
        creationConfirmation.value = null
        if (currentScrobbler.scrobblerService != pending.service || selectedItemId.value != pending.targetId) return
        runCatching { currentScrobbler.trackerRepository.storage.checkSession(pending.session) }.onSuccess {
            linkSelected(allowCreate = true)
        }
    }

    private fun linkSelected(allowCreate: Boolean) {
        if (doneJob?.isActive == true || availableScrobblers.isEmpty()) return
        val targetId = selectedItemId.value
        if (targetId == NO_ID) {
            onClose.call(Unit)
            return
        }
        val tracker = currentScrobbler
        doneJob = launchLoadingJob(Dispatchers.IO) {
            tracker.withAccount {
                val session = tracker.trackerRepository.storage.snapshot()
                try {
                    dataRepository.storeManga(manga, replaceExisting = false)
                    tracker.linkManga(manga.id, targetId, allowCreate)
                    val account = requireNotNull(session.accountId)
                    tracker.trackerRepository.storage.progress.invalidate(account, manga.id, manual = true)
                    libraryRepository.invalidate(tracker.scrobblerService, account)
                    onClose.call(Unit)
                } catch (e: org.koitharu.kotatsu.scrobbling.common.data.TrackerEntryCreationRequired) {
                    creationConfirmation.value = CreateConfirmation(tracker.scrobblerService, targetId, session)
                }
            }
        }
    }

    fun setScrobblerIndex(index: Int) {
        if (index == selectedScrobblerIndex.value || index !in availableScrobblers.indices) return
        doneJob?.cancel()
        creationConfirmation.value = null
        selectedScrobblerIndex.value = index
        selectedItemId.value = NO_ID
        initialize()
    }

    private fun initialize() {
        searchRevision++
        val tracker = currentScrobbler
        initJob?.cancel()
        loadingJob?.cancel()
        hasNextPage.value = true
        scrobblerMangaList.value = emptyList()
        resolvedSearchQuery.value = null
        initJob = launchJob(Dispatchers.Default) {
            try {
                val info = tracker.getScrobblingInfoOrNull(manga.id)
                if (preferredTarget != null && tracker.scrobblerService.id == preferredService) {
                    selectedItemId.value = preferredTarget
                    val target = tracker.withAccount { tracker.trackerRepository.getMangaInfo(preferredTarget) }
                    scrobblerMangaList.value = listOf(ScrobblerManga(target.id, target.name, null, target.cover, target.url, false))
                    hasNextPage.value = false
                } else selectedItemId.value = info?.targetId ?: NO_ID
            } finally {
                if (scrobblerMangaList.value.isEmpty()) loadList(append = false)
            }
        }
    }

    private suspend fun findManga(tracker: Scrobbler, offset: Int): Pair<String, List<ScrobblerManga>> {
        val query = searchQuery.requireValue()
        if (offset > 0) {
            val resolvedQuery = resolvedSearchQuery.value ?: query
            return resolvedQuery to tracker.findManga(resolvedQuery, offset)
        }
        var lastQuery = query
        for (candidate in getSearchQueries(query)) {
            lastQuery = candidate
            val list = tracker.findManga(candidate, offset)
            if (list.isNotEmpty()) {
                return candidate to list
            }
        }
        return lastQuery to emptyList()
    }

    private fun getSearchQueries(query: String): List<String> {
        if (query != manga.title) {
            return listOf(query)
        }
        return buildList {
            add(query)
            manga.altTitles
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { it != query }
                .forEach(::add)
        }.distinct()
    }

    private fun emptyResultsHint() = ScrobblerHint(
        icon = R.drawable.ic_empty_history,
        textPrimary = R.string.nothing_found,
        textSecondary = R.string.text_search_holder_secondary,
        error = null,
        actionStringRes = R.string.search,
    )

    private fun errorHint(e: Throwable): ScrobblerHint {
        val resolveAction = ExceptionResolver.getResolveStringId(e)
        return ScrobblerHint(
            icon = R.drawable.ic_error_large,
            textPrimary = R.string.error_occurred,
            error = e,
            textSecondary = if (resolveAction == 0) 0 else R.string.try_again,
            actionStringRes = resolveAction.ifZero { R.string.try_again },
        )
    }
}
