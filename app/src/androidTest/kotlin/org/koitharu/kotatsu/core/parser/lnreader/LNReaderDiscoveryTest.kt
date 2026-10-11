package org.koitharu.kotatsu.core.parser.lnreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

@RunWith(AndroidJUnit4::class)
class LNReaderDiscoveryTest {
    @Test fun discoveryPreservesBrowsePaginationStorageAndSourceSort() = fixture { repository, storage ->
        repository.defaultSortOrder = SortOrder.UPDATED
        val pageOne = repository.getList(0, null, null)
        val before = storage.keys().associateWith { storage.get(it) }
        assertEquals("latest:1", pageOne.single().title)
        assertEquals("find:1", repository.discover(SortOrder.POPULARITY, MangaListFilter(query = "find")).single().title)
        assertEquals("popular:1", repository.discover(SortOrder.POPULARITY, MangaListFilter()).single().title)
        assertEquals(before, storage.keys().associateWith { storage.get(it) })
        assertEquals(SortOrder.UPDATED, repository.defaultSortOrder)
        assertEquals("latest:2", repository.getList(1, null, null).single().title)
    }

    @Test fun metadataDoesNotFetchChapterPaginationPagesOrContent() = fixture { repository, storage ->
        val manga = repository.discover(SortOrder.POPULARITY, MangaListFilter()).single()
        val metadata = repository.getLightweightMetadata(manga)
        assertEquals(manga.id, metadata.id)
        assertEquals(manga.url, metadata.url)
        assertNull(metadata.chapters)
        assertEquals(setOf("Fantasy"), metadata.tags.map { it.title }.toSet())
        assertEquals(setOf("Fixture author"), metadata.authors)
        assertTrue(storage.keys().isEmpty())
    }

    @Test fun cancellationAndFailureReleasePluginContextForTheNextRequest() = fixture { repository, _ ->
        assertTrue(runCatching {
            withTimeout(300) { repository.discover(SortOrder.POPULARITY, MangaListFilter(query = "wait")) }
        }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(runCatching { repository.discover(SortOrder.POPULARITY, MangaListFilter(query = "fail")) }.isFailure)
        assertEquals("ok:1", withTimeout(3000) {
            repository.discover(SortOrder.POPULARITY, MangaListFilter(query = "ok"))
        }.single().title)
    }

    private fun fixture(block: suspend (LnReaderMangaRepository, LNReaderStorage) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cache = MemoryContentCache(app)
        val storage = LNReaderStorage()
        val entity = LnReaderSourceEntity("recommendation-fixture", "Fixture", site = "https://fixture.invalid",
            jsCode = """
                var storage = require('@libs/storage').storage;
                function item(name) { return [{name: name, path: '/title/' + name}]; }
                exports.default = {
                    popularNovels: function(page, options) {
                        storage.set('last-browse', page);
                        return item((options.showLatestNovels ? 'latest' : 'popular') + ':' + page);
                    },
                    searchNovels: function(query, page) {
                        storage.set('last-search', query);
                        if (query === 'wait') return new Promise(function() {});
                        if (query === 'fail') throw new Error('Fixture failure');
                        return item(query + ':' + page);
                    },
                    parseNovel: function(path) {
                        storage.set('last-novel', path);
                        return {name: 'Metadata', path: path, author: 'Fixture author', genres: ['Fantasy'],
                            totalPages: 100, chapters: [{name: 'Chapter', path: '/chapter'}]};
                    },
                    parsePage: function() { throw new Error('Must not paginate chapters'); },
                    parseChapter: function() { throw new Error('Must not load or translate chapter content'); }
                };
            """.trimIndent(), createdAt = 0, updatedAt = 0)
        try { block(LnReaderMangaRepository(entity, OkHttpClient(), cache, storage = storage), storage) }
        finally { app.unregisterComponentCallbacks(cache) }
    }
}
