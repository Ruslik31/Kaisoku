package org.koitharu.kotatsu.scrobbling.anilist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.scrobbling.common.data.TrackerLibraryRepository
import org.koitharu.kotatsu.scrobbling.common.data.TrackerSessionChangedException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryFilter
import javax.inject.Inject

@HiltViewModel
class AniListLibraryViewModel @Inject constructor(
    val repository: TrackerLibraryRepository,
    private val savedState: SavedStateHandle,
) : BaseViewModel() {
    val entries = MutableStateFlow<List<TrackerLibraryEntry>>(emptyList())
    val selectedStatus = MutableStateFlow(savedState.get<String>("library_status"))
    val search = MutableStateFlow(savedState.get<String>("library_search").orEmpty())
    val sortByTitle = MutableStateFlow(savedState.get<Boolean>("library_sort") ?: false)
    val service = MutableStateFlow(savedState.get<ScrobblerService>("library_service") ?: ScrobblerService.ANILIST)
    val error = MutableStateFlow<Throwable?>(null)
    val isConnected = MutableStateFlow(false)
    val userName = MutableStateFlow<String?>(null)
    val syncMessage = MutableStateFlow<Pair<Int, String?>>(0 to null)
    val content = combine(entries, selectedStatus, search, sortByTitle, TrackerLibraryFilter::apply)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var job: Job? = null
    private var watcher: Job? = null
    @Volatile private var generation = 0L
    private var displayedAccount: Pair<ScrobblerService, Long?>? = null

    init { watchAccount() }

    fun selectService(value: ScrobblerService) {
        if (value == service.value) return
        generation++
        job?.cancel()
        service.value = value
        savedState["library_service"] = value
        entries.value = emptyList()
        error.value = null
        watchAccount()
        refreshIfStale()
    }

    fun setStatus(value: String?) { selectedStatus.value = value; savedState["library_status"] = value }
    fun setSearch(value: String) { search.value = value; savedState["library_search"] = value }
    fun toggleSort() { sortByTitle.value = !sortByTitle.value; savedState["library_sort"] = sortByTitle.value }
    fun refreshIfStale() = load(false)
    fun refresh() = load(true)

    private fun watchAccount() {
        watcher?.cancel()
        val selected = service.value
        watcher = viewModelScope.launch {
            repository.scrobbler(selected).trackerRepository.storage.sessionChanges.collect {
                if (selected == service.value) {
                    val tracker = repository.scrobbler(selected).trackerRepository
                    val owner = selected to tracker.cachedUser?.id
                    val previous = displayedAccount
                    if (!tracker.isAuthorized || (previous?.second != null && previous != owner)) {
                        generation++
                        job?.cancel()
                        entries.value = emptyList()
                        error.value = null
                        syncMessage.value = 0 to null
                    }
                    displayedAccount = owner
                    if (tracker.isAuthorized && owner.second != null && previous != owner && job?.isActive != true) {
                        refreshIfStale()
                    }
                    isConnected.value = tracker.isAuthorized
                    userName.value = tracker.cachedUser?.nickname
                }
            }
        }
    }

    private fun load(force: Boolean) {
        if (job?.isActive == true && !force) return
        job?.cancel()
        val selected = service.value
        val revision = ++generation
        job = launchLoadingJob(Dispatchers.IO) {
            error.value = null
            val tracker = repository.scrobbler(selected).trackerRepository
            isConnected.value = tracker.isAuthorized
            if (!tracker.isAuthorized) {
                entries.value = emptyList()
                userName.value = null
                return@launchLoadingJob
            }
            try {
                // Offline display uses the last known account; remote writes still require validation.
                val knownSession = tracker.storage.snapshot()
                if (knownSession.accountId != null) {
                    val cached = repository.cached(selected, knownSession)
                    tracker.storage.checkSession(knownSession)
                    if (revision != generation || selected != service.value) return@launchLoadingJob
                    displayedAccount = selected to knownSession.accountId
                    userName.value = tracker.cachedUser?.nickname
                    if (cached != null) entries.value = cached
                    syncMessage.value = tracker.storage.progress.pendingCount(knownSession.accountId) to
                        tracker.storage.progress.error(knownSession.accountId)
                }
                val session = repository.session(selected)
                if (revision != generation || selected != service.value) return@launchLoadingJob
                displayedAccount = selected to session.accountId
                userName.value = tracker.cachedUser?.nickname
                repository.cached(selected, session)?.let { entries.value = it }
                val owner = requireNotNull(session.accountId)
                syncMessage.value = tracker.storage.progress.pendingCount(owner) to tracker.storage.progress.error(owner)
                val fresh = repository.refresh(selected, session, force)
                tracker.storage.checkSession(session)
                if (revision == generation && selected == service.value) entries.value = fresh
            } catch (e: TrackerSessionChangedException) {
                if (revision == generation) { entries.value = emptyList(); isConnected.value = tracker.isAuthorized }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (revision == generation) error.value = e
            }
        }
    }
}
