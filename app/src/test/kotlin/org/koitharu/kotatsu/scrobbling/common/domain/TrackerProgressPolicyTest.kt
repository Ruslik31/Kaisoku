package org.koitharu.kotatsu.scrobbling.common.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

class TrackerProgressPolicyTest {
    @Test fun webtoonBottomOffsetAloneDoesNotProveChapterCompletion() {
        assertFalse(isMangaChapterCompleted(43, 45, 10_000, true))
        assertFalse(isMangaChapterCompleted(44, 45, 9_999, true))
        assertTrue(isMangaChapterCompleted(44, 45, 10_000, true))
    }

    @Test fun zeroBasedFinalPagedPositionNeedsARealPageCount() {
        assertTrue(isMangaChapterCompleted(44, 45, 0, false))
        assertFalse(isMangaChapterCompleted(45, 45, 0, false))
        assertFalse(isMangaChapterCompleted(0, 0, 10_000, true))
        assertFalse(isMangaChapterCompleted(-1, 1, 0, false))
    }

    @Test fun chapterNumbersTakePriorityOverPositionsAndRejectInvalidNumbers() {
        fun chapter(id: Long, number: Float) = MangaChapter(id, "Chapter", number, 0,
            "https://example.org/$id", null, 0L, null, MangaParserSource.MANGAKIO)
        val manga = Manga(id = -88, title = "Fixture", altTitles = emptySet(), url = "/title",
            publicUrl = "https://example.org/title", rating = RATING_UNKNOWN, contentRating = ContentRating.SAFE,
            coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), source = MangaParserSource.MANGAKIO,
            chapters = listOf(chapter(1, 40f), chapter(2, 0.5f), chapter(3, Float.NaN), chapter(4, Float.POSITIVE_INFINITY)))
        assertEquals(40, completedTrackerChapter(manga, 1))
        assertNull(completedTrackerChapter(manga, 2))
        assertNull(completedTrackerChapter(manga, 3))
        assertNull(completedTrackerChapter(manga, 4))
        assertNull(completedTrackerChapter(manga, 99))
        assertEquals(40, completedTrackerChapter(manga.copy(chapters = manga.chapters!!.reversed()), 1))
    }
}
