package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.*

class ChaptersLoaderTest {
    @Test fun pageLoadsReceiveTheCurrentTitleContext() = runTest {
        val fixture = Fixture()
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(2)
        fixture.loader.loadPrevNextChapter(fixture.details, 2, true)
        assertEquals(listOf("/fixture", "/fixture"), fixture.titleContexts)
    }

    @Test fun repeatedForwardAndBackwardRequestsLoadOnlyOnce() = runTest {
        val fixture = Fixture()
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(2)
        assertTrue(fixture.loader.loadPrevNextChapter(fixture.details, 2, true))
        assertFalse(fixture.loader.loadPrevNextChapter(fixture.details, 2, true))
        assertTrue(fixture.loader.loadPrevNextChapter(fixture.details, 2, false))
        assertFalse(fixture.loader.loadPrevNextChapter(fixture.details, 2, false))
        assertEquals(listOf(1L, 2L, 3L), fixture.loader.snapshot().map { it.chapterId }.distinct())
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 1), fixture.calls)
    }

    @Test fun trimmingOldPagesKeepsTheVisibleChapterAndPrefetchBoundary() = runTest {
        val fixture = Fixture(pagesCount = 70)
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(1)
        fixture.loader.loadPrevNextChapter(fixture.details, 1, true)
        val visible = fixture.loader.snapshot()[75]
        assertTrue(fixture.loader.loadPrevNextChapter(fixture.details, 2, true))
        assertEquals(listOf(2L, 3L), fixture.loader.snapshot().map { it.chapterId }.distinct())
        assertEquals(visible, fixture.loader.snapshot()[5])
        assertEquals(10, fixture.loader.snapshot().nextChapterPrefetchPages(2, 10).size)
    }

    @Test fun emptyAndFailedSuccessorsPreserveTheLoadedPages() = runTest {
        val fixture = Fixture()
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(2)
        val original = fixture.loader.snapshot()
        fixture.fetch = { emptyList() }
        assertFalse(fixture.loader.loadPrevNextChapter(fixture.details, 2, true))
        assertEquals(original, fixture.loader.snapshot())
        fixture.fetch = { error("offline") }
        try {
            fixture.loader.loadPrevNextChapter(fixture.details, 2, true)
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertEquals(original, fixture.loader.snapshot())
        }
    }

    @Test fun concurrentDuplicateCompletionDoesNotTrimTheCurrentChapter() = runTest {
        val fixture = Fixture(pagesCount = 70)
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(1)
        fixture.loader.loadPrevNextChapter(fixture.details, 1, true)
        val started = CompletableDeferred<Unit>()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.fetch = { chapter ->
            started.complete(Unit)
            if (fixture.calls[chapter.id] == 2) bothStarted.complete(Unit)
            release.await()
            fixture.pages(chapter)
        }
        val first = async { fixture.loader.loadPrevNextChapter(fixture.details, 2, true) }
        started.await()
        val duplicate = async { fixture.loader.loadPrevNextChapter(fixture.details, 2, true) }
        bothStarted.await()
        release.complete(Unit)
        assertEquals(1, listOf(first.await(), duplicate.await()).count { it })
        assertEquals(listOf(2L, 3L), fixture.loader.snapshot().map { it.chapterId }.distinct())
    }

    @Test fun cancelledFetchCannotAppendPagesAfterItReturns() = runTest {
        val fixture = Fixture()
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(2)
        val original = fixture.loader.snapshot()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.fetch = { chapter ->
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                fixture.pages(chapter)
            }
        }
        val preload = launch { fixture.loader.loadPrevNextChapter(fixture.details, 2, true) }
        started.await()
        preload.cancel()
        release.complete(Unit)
        preload.join()
        assertEquals(original, fixture.loader.snapshot())
    }

    @Test fun reversePreloadAppendsPreviousNumberWithoutChangingTheVisiblePages() = runTest {
        val fixture = Fixture()
        fixture.loader.init(fixture.details)
        fixture.loader.loadSingleChapter(2)
        val original = fixture.loader.snapshot()
        assertTrue(fixture.loader.loadPrevNextChapter(fixture.details, 2, true, reversed = true))
        assertEquals(original, fixture.loader.snapshot().take(original.size))
        assertEquals(1L, fixture.loader.snapshot().nextChapterPrefetchPages(2, 10).first().chapterId)
    }

    private class Fixture(private val pagesCount: Int = 10) {
        val chapters = List(3) { index ->
            MangaChapter(index + 1L, "Chapter ${index + 1}", index + 1f, 0,
                "https://example.org/${index + 1}", null, 0L, null, TestMangaSource)
        }
        val details = MangaDetails(Manga(id = 1, title = "Fixture", altTitles = emptySet(),
            url = "/fixture", publicUrl = "https://example.org/fixture", rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE, coverUrl = null, tags = emptySet(), state = null,
            authors = emptySet(), source = TestMangaSource, chapters = chapters))
        val calls = mutableMapOf<Long, Int>()
        val titleContexts = mutableListOf<String>()
        var fetch: suspend (MangaChapter) -> List<MangaPage> = ::pages
        private val repository = object : EmptyMangaRepository(TestMangaSource) {
            override suspend fun getPages(manga: Manga, chapter: MangaChapter): List<MangaPage> {
                titleContexts += manga.url
                return getPages(chapter)
            }
            override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
                calls[chapter.id] = (calls[chapter.id] ?: 0) + 1
                return fetch(chapter)
            }
        }
        val loader = ChaptersLoader { repository }

        fun pages(chapter: MangaChapter) = List(pagesCount) { index ->
            MangaPage(chapter.id * 1000 + index, "https://example.org/${chapter.id}/$index", null, TestMangaSource)
        }
    }
}
