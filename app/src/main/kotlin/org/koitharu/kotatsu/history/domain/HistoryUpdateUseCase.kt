package org.koitharu.kotatsu.history.domain

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import javax.inject.Inject

class HistoryUpdateUseCase @Inject constructor(
	private val historyRepository: HistoryRepository,
) {
	private var lastAsyncUpdate: Job? = null

	suspend operator fun invoke(manga: Manga, readerState: ReaderState, percent: Float) {
		historyRepository.addOrUpdate(
			manga = manga,
			chapterId = readerState.chapterId,
			page = readerState.page,
			scroll = readerState.scroll,
			percent = percent,
			force = false,
		)
	}

	@Synchronized
	fun invokeAsync(
		manga: Manga,
		readerState: ReaderState,
		percent: Float
	): Job {
		val previousUpdate = lastAsyncUpdate
		return processLifecycleScope.launch(Dispatchers.Default, CoroutineStart.ATOMIC) {
			// Pause, stop, and idle can save in quick succession. Preserve their call order so an
			// older database transaction cannot finish after and overwrite a newer reading position.
			previousUpdate?.join()
			runCatchingCancellable {
				withContext(NonCancellable) {
					invoke(manga, readerState, percent)
				}
			}.onFailure {
				it.printStackTraceDebug()
			}
		}.also { lastAsyncUpdate = it }
	}
}
