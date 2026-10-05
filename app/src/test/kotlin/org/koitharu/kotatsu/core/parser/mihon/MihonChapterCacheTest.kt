package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.TestMangaSource

class MihonChapterCacheTest {
    @Test fun identicalChapterUrlsRetainTheirOwnExtensionObjects() {
        val cache = MihonChapterCache()
        val first = SChapter.create().apply { url = "/1"; name = "First title" }
        val second = SChapter.create().apply { url = "/1"; name = "Second title" }
        cache.put("/first", listOf(first))
        cache.put("/second", listOf(second))
        assertSame(first, cache.get("/first", "/1"))
        assertSame(second, cache.get("/second", "/1"))
    }

    @Test fun refreshedChaptersReplaceOldMetadataAndEvictionIsBounded() {
        val cache = MihonChapterCache(2)
        val chapter = SChapter.create().apply { url = "/1" }
        cache.put("/a", listOf(chapter))
        cache.put("/b", listOf(chapter))
        assertSame(chapter, cache.get("/a", "/1"))
        cache.put("/c", listOf(chapter))
        assertNull(cache.get("/b", "/1"))
        cache.put("/a", emptyList())
        assertNull(cache.get("/a", "/1"))
    }

    @Test fun pageCacheKeysIncludeTitleContextWithoutStringConcatenationCollisions() {
        val source = TestMangaSource
        assertNotEquals(MemoryContentCache.Key(source, "/1", "/a"), MemoryContentCache.Key(source, "/1", "/b"))
        assertNotEquals(MemoryContentCache.Key(source, "a:b", "c"), MemoryContentCache.Key(source, "b", "c:a"))
        assertNotEquals(MemoryContentCache.Key(source, "/1"), MemoryContentCache.Key(source, "/1", "/a"))
    }
}
