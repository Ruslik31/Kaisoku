package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

class ChapterPrefetchTest {
    @Test fun successorIsBoundedByItsOwnChapterAndLimit() {
        assertEquals(chapter(30, 20).take(10),
            (chapter(29, 15) + chapter(30, 20)).nextChapterPrefetchPages(29, 10))
        assertEquals(chapter(30, 2),
            (chapter(29, 15) + chapter(30, 2) + chapter(31, 15)).nextChapterPrefetchPages(29, 10))
    }

    @Test fun trimmingEarlierChaptersDoesNotChangeTheSuccessor() {
        val pages = chapter(28, 100) + chapter(29, 30) + chapter(30, 12)
        assertEquals(pages.nextChapterPrefetchPages(29, 10),
            pages.drop(100).nextChapterPrefetchPages(29, 10))
    }

    @Test fun reverseReadingUsesTheAppendedChapterRatherThanItsNumber() {
        assertEquals(chapter(28, 4),
            (chapter(29, 15) + chapter(28, 4)).nextChapterPrefetchPages(29, 10))
    }

    @Test fun missingBoundariesAndEmptySnapshotsDoNotPrefetchAnotherTitle() {
        assertTrue(emptyList<ReaderPage>().nextChapterPrefetchPages(29, 10).isEmpty())
        assertTrue(chapter(29, 15).nextChapterPrefetchPages(29, 10).isEmpty())
        assertTrue(chapter(30, 15).nextChapterPrefetchPages(29, 10).isEmpty())
        assertTrue((chapter(29, 15) + chapter(30, 15)).nextChapterPrefetchPages(29, 0).isEmpty())
    }

    private fun chapter(id: Long, count: Int) = List(count) { index ->
        ReaderPage(id * 1000 + index, "https://example.org/$id/$index", null, id, index, TestMangaSource)
    }
}
