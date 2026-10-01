package org.koitharu.kotatsu.stats.domain

import androidx.collection.LongSparseArray
import androidx.collection.set
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.RetainedLifecycleCoroutineScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.stats.data.StatsEntity
import javax.inject.Inject

@ViewModelScoped
class StatsCollector @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	lifecycle: ViewModelLifecycle,
) {

	private val viewModelScope = RetainedLifecycleCoroutineScope(lifecycle)
	private val stats = LongSparseArray<Entry>(1)
	private var lastCommit: Job? = null

	@Synchronized
	fun onStateChanged(mangaId: Long, state: ReaderState, historyUpdate: Job? = null) {
		if (!settings.isStatsEnabled) {
			return
		}
		val now = System.currentTimeMillis()
		val entry = stats[mangaId]
		if (entry == null) {
			stats[mangaId] = Entry(
				session = ReadingStatsSession.start(mangaId, state, now),
				historyUpdate = historyUpdate,
			)
			return
		}
		val newEntry = entry.copy(
			session = entry.session.advance(state, now),
			historyUpdate = historyUpdate ?: entry.historyUpdate,
		)
		stats[mangaId] = newEntry
		commit(newEntry.session.stats, newEntry.historyUpdate)
	}

	@Synchronized
	fun onPause(mangaId: Long) {
		val entry = stats[mangaId] ?: return
		stats.remove(mangaId)
		if (settings.isStatsEnabled) {
			commit(entry.session.advance(entry.session.state, System.currentTimeMillis()).stats, entry.historyUpdate)
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	private fun commit(entity: StatsEntity, historyUpdate: Job?) {
		val previousCommit = lastCommit
		lastCommit = viewModelScope.launch(Dispatchers.Default, CoroutineStart.ATOMIC) {
			runCatchingCancellable {
				// Finish the final pause write even when the reader's ViewModel is cleared.
				withContext(NonCancellable) {
					previousCommit?.join()
					// Statistics reference history; a first novel session must wait for that row.
					historyUpdate?.join()
					db.getStatsDao().upsert(entity)
				}
			}.onFailure { e ->
				e.printStackTraceDebug()
			}
		}
	}

	private data class Entry(
		val session: ReadingStatsSession,
		val historyUpdate: Job?,
	)
}
