package org.koitharu.kotatsu.reader.ui.pager

import android.content.res.Resources
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.parsers.model.MangaChapter

data class ReaderUiState(
	val mangaName: String?,
	val chapter: MangaChapter,
	val chapterIndex: Int,
	val chaptersTotal: Int,
	val currentPage: Int,
	val totalPages: Int,
	val percent: Float,
	val incognito: Boolean,
	val scrollProgress: Float = -1f,
	val chaptersReversed: Boolean = false,
) {

	val chapterNumber: Int
		get() = chapterIndex + 1

	fun hasNextChapter(): Boolean = chapterIndex in 0 until chaptersTotal &&
		if (chaptersReversed) chapterIndex > 0 else chapterNumber < chaptersTotal

	fun hasPreviousChapter(): Boolean = chapterIndex in 0 until chaptersTotal &&
		if (chaptersReversed) chapterNumber < chaptersTotal else chapterIndex > 0

	fun isSliderAvailable(): Boolean = totalPages > 1 && currentPage < totalPages

	fun getChapterTitle(resources: Resources) = chapter.getLocalizedTitle(resources, chapterIndex)
}
