package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
	private val repository: TrackingRepository,
) {

	suspend operator fun invoke(limit: Int): List<MangaTracking> {
		repository.updateTracks()
		val now = System.currentTimeMillis()
		return repository.getTracks(
			offset = 0,
			limit = limit,
			// Skip tracks with no fresh chapters or user activity in the last MAX_INACTIVE_DAYS so the
			// periodic worker doesn't burn requests on long-stalled or abandoned series.
			minActivityTime = now - TimeUnit.DAYS.toMillis(MAX_INACTIVE_DAYS),
			staleCheckTime = now - TimeUnit.DAYS.toMillis(STALE_CHECK_DAYS),
		)
	}

	private companion object {
		const val MAX_INACTIVE_DAYS = 90L
		const val STALE_CHECK_DAYS = 7L
	}
}
