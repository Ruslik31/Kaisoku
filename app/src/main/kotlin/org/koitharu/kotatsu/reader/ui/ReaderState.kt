package org.koitharu.kotatsu.reader.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.Manga

@Parcelize
data class ReaderState(
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
) : Parcelable {

	constructor(history: MangaHistory) : this(
		chapterId = history.chapterId,
		page = history.page,
		scroll = history.scroll,
	)

	constructor(manga: Manga, branch: String?) : this(
		chapterId = manga.chapters?.let {
			it.firstOrNull { x -> x.branch == branch } ?: it.firstOrNull()
		}?.id ?: error("Cannot find first chapter"),
		page = 0,
		scroll = 0,
	)
}

/** A visible reader position paired with the page-list generation that produced it. */
data class ReaderStateSnapshot(
	val state: ReaderState?,
	val contentGeneration: Long,
)

internal fun ReaderStateSnapshot?.stateForGeneration(generation: Long): ReaderState? =
	this?.takeIf { it.contentGeneration == generation }?.state
