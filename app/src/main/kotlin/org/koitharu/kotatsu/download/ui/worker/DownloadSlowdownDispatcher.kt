package org.koitharu.kotatsu.download.ui.worker

import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadControlValues
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
) {
	private val nextRequestAt = MutableObjectLongMap<MangaSource>()

	suspend fun delay(source: MangaSource) {
		val repo = mangaRepositoryFactory.create(source) as? ParserMangaRepository
		val interval = DownloadControlValues.effectiveRequestInterval(
			globalInterval = settings.downloadRequestInterval,
			sourceSlowdownEnabled = repo?.isSlowdownEnabled() == true,
		)
		if (interval <= 0L) {
			return
		}
		awaitDownloadRequestSlot(nextRequestAt, source, interval, SystemClock::elapsedRealtime)
	}
}

internal suspend fun awaitDownloadRequestSlot(
	nextRequestAt: MutableObjectLongMap<MangaSource>,
	source: MangaSource,
	interval: Long,
	now: () -> Long,
) {
	currentCoroutineContext().ensureActive()
	val time = now()
	val slot = reserveSlowdownSlot(nextRequestAt, source, time, interval)
	delay((slot - time).coerceAtLeast(0L))
	currentCoroutineContext().ensureActive()
}

internal fun reserveSlowdownSlot(
	nextRequestAt: MutableObjectLongMap<MangaSource>,
	source: MangaSource,
	now: Long,
	interval: Long,
): Long = synchronized(nextRequestAt) {
	val slot = maxOf(nextRequestAt.getOrDefault(source, now), now)
	nextRequestAt[source] = slot + interval
	slot
}
