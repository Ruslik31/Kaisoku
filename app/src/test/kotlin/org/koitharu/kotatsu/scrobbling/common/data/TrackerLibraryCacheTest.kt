package org.koitharu.kotatsu.scrobbling.common.data

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry

class TrackerLibraryCacheTest {
    private fun entry(service: ScrobblerService, title: String = "Title") = TrackerLibraryEntry(
        service, 15, 12, title, "cover", "url", "PAUSED", 40, 100, 8.5f, "Website notes", 100,
        setOf("Alias"), "MANGA")

    @Test fun offlineEntriesSurviveRestartWithoutLocalMangaRecords() {
        val prefs = MemoryPreferences(); val item = entry(ScrobblerService.ANILIST)
        TrackerLibraryCache(prefs).write(ScrobblerService.ANILIST, 1, listOf(item), 1_000)
        assertEquals(listOf(item), TrackerLibraryCache(prefs).read(ScrobblerService.ANILIST, 1))
        assertNull(item.localMangaId)
    }

    @Test fun servicesAndAccountsNeverShareCachedContent() {
        val cache = TrackerLibraryCache(MemoryPreferences())
        cache.write(ScrobblerService.ANILIST, 1, listOf(entry(ScrobblerService.ANILIST, "First")), 1_000)
        cache.write(ScrobblerService.ANILIST, 2, listOf(entry(ScrobblerService.ANILIST, "Second")), 2_000)
        cache.write(ScrobblerService.MAL, 1, listOf(entry(ScrobblerService.MAL, "Other service")), 3_000)
        assertEquals("First", cache.read(ScrobblerService.ANILIST, 1)?.single()?.title)
        assertEquals("Second", cache.read(ScrobblerService.ANILIST, 2)?.single()?.title)
        assertEquals("Other service", cache.read(ScrobblerService.MAL, 1)?.single()?.title)
        assertNull(cache.read(ScrobblerService.KITSU, 1))
    }

    @Test fun expirationAndInvalidationRetainContentForFailedRefreshes() {
        val cache = TrackerLibraryCache(MemoryPreferences()); val item = entry(ScrobblerService.ANILIST)
        cache.write(ScrobblerService.ANILIST, 1, listOf(item), 1_000)
        assertTrue(cache.isFresh(ScrobblerService.ANILIST, 1, 300_999))
        assertFalse(cache.isFresh(ScrobblerService.ANILIST, 1, 301_000))
        assertFalse(cache.isFresh(ScrobblerService.ANILIST, 1, 999))
        cache.invalidate(ScrobblerService.ANILIST, 1)
        assertFalse(cache.isFresh(ScrobblerService.ANILIST, 1, 1_001))
        assertEquals(listOf(item), cache.read(ScrobblerService.ANILIST, 1))
    }

    @Test fun anEmptyLibraryIsDifferentFromAMissingOrMalformedCache() {
        val prefs = MemoryPreferences(); val cache = TrackerLibraryCache(prefs)
        assertNull(cache.read(ScrobblerService.ANILIST, 1))
        cache.write(ScrobblerService.ANILIST, 1, emptyList(), 1_000)
        assertEquals(emptyList<TrackerLibraryEntry>(), cache.read(ScrobblerService.ANILIST, 1))
        prefs.edit().putString("ANILIST_1", "broken JSON").apply()
        assertNull(cache.read(ScrobblerService.ANILIST, 1))
    }
}
