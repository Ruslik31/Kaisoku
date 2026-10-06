package org.koitharu.kotatsu.scrobbling.common.domain

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById

/** Chapter numbers, not list positions, are the tracker contract when the source supplies numbers. */
internal fun completedTrackerChapter(manga: Manga, chapterId: Long): Int? {
    val chapters = manga.chapters ?: return null
    val chapter = chapters.findById(chapterId) ?: return null
    if (!chapter.number.isFinite()) return null
    return if (chapter.number >= 1f) chapter.number.toInt()
    else if (chapter.number > 0f) null // Decimal/prologue chapters do not advance a whole chapter.
    else chapters.filter { it.branch == chapter.branch }.indexOf(chapter).takeIf { it >= 0 }?.plus(1)
}

internal fun isMangaChapterCompleted(page: Int, pageCount: Int, scroll: Int, webtoon: Boolean): Boolean =
    pageCount > 0 && page == pageCount - 1 && (!webtoon || scroll == 10_000)
