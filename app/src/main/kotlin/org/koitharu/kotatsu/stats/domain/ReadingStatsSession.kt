package org.koitharu.kotatsu.stats.domain

import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.stats.data.StatsEntity

internal data class ReadingStatsSession(val state: ReaderState, val stats: StatsEntity) {

	fun advance(state: ReaderState, now: Long): ReadingStatsSession {
		val pagesDelta = if (this.state.page != state.page || this.state.chapterId != state.chapterId) 1 else 0
		return copy(
			state = state,
			stats = stats.copy(duration = (now - stats.startedAt).coerceAtLeast(0), pages = stats.pages + pagesDelta),
		)
	}

	companion object {
		fun start(mangaId: Long, state: ReaderState, now: Long) = ReadingStatsSession(
			state = state,
			stats = StatsEntity(mangaId = mangaId, startedAt = now, duration = 0, pages = 0),
		)
	}
}
