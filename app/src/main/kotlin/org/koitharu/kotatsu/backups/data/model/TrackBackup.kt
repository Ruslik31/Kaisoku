package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.tracker.data.TrackEntity

@Serializable
data class TrackBackup(
	@SerialName("manga") val manga: MangaBackup,
	@SerialName("last_chapter_id") val lastChapterId: Long,
	@SerialName("new_chapters") val newChapters: Int,
	@SerialName("last_check_time") val lastCheckTime: Long,
	@SerialName("last_chapter_date") val lastChapterDate: Long,
	@SerialName("last_result") val lastResult: Int,
	@SerialName("last_error") val lastError: String?,
) {
	constructor(manga: MangaWithTags, track: TrackEntity) : this(
		manga = MangaBackup(manga),
		lastChapterId = track.lastChapterId,
		newChapters = track.newChapters,
		lastCheckTime = track.lastCheckTime,
		lastChapterDate = track.lastChapterDate,
		lastResult = track.lastResult,
		lastError = track.lastError,
	)

	fun toEntity() = TrackEntity(
		mangaId = manga.id,
		lastChapterId = lastChapterId,
		newChapters = newChapters,
		lastCheckTime = lastCheckTime,
		lastChapterDate = lastChapterDate,
		lastResult = lastResult,
		lastError = lastError,
	)
}
