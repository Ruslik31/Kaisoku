package org.koitharu.kotatsu.scrobbling.common.domain.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackerLibraryEntryTest {
    private fun entry(id: Long = 1, status: String = "CURRENT", title: String = "Title", updated: Long = 1) =
        TrackerLibraryEntry(ScrobblerService.ANILIST, id, id.toInt(), title, "cover", "url", status, 5, null,
            8.5f, "Notes", updated, setOf("Alias", "日本語"), "MANGA", -88L)

    @Test fun fullCacheRoundTripPreservesAliasesRemoteFieldsAndNegativeLocalIdentities() {
        val original = entry()
        assertEquals(original, TrackerLibraryEntry.fromJson(JSONObject(original.toJson().toString())))
        assertEquals(original.copy(localMangaId = null, notes = null), TrackerLibraryEntry.fromJson(
            original.copy(localMangaId = null, notes = null).toJson()))
    }

    @Test fun anilistLegacyCacheUsesTheFlatConstructorAndPreservesNegativeIds() {
        val original = AniListLibraryEntry(12, 15, "Title", "cover", "url", "CURRENT", 5, null, 8f, null,
            1, -88, setOf("Alias"), "MANGA")
        assertEquals(original, AniListLibraryEntry(JSONObject(original.toJson().toString()), cached = true))
    }

    @Test fun anilistApiNullsDoNotBecomeLiteralNullNotesOrAliases() {
        val original = AniListLibraryEntry(JSONObject("""{"id":12,"status":"CURRENT","notes":null,"score":8,
            "media":{"id":15,"title":{"userPreferred":"Title","native":null},"synonyms":[null,"Alias"],"coverImage":{}}}"""))
        assertNull(original.notes); assertEquals(setOf("Title", "Alias"), original.aliases)
    }

    @Test fun allStatusGroupsCountsAliasSearchAndSortingAreLocal() {
        val statuses = listOf("CURRENT", "COMPLETED", "PLANNING", "PAUSED", "DROPPED", "REPEATING")
        val entries = statuses.mapIndexed { i, status -> entry(i + 1L, status, "Title $i", i.toLong()) }
        assertEquals(6, TrackerLibraryFilter.apply(entries, null, "", false).size)
        statuses.forEach { assertEquals(1, TrackerLibraryFilter.apply(entries, it, "", false).size) }
        assertEquals(1, TrackerLibraryFilter.apply(entries, "PAUSED", "日本", false).size)
        assertEquals("Title 5", TrackerLibraryFilter.apply(entries, null, "", false).first().title)
        assertEquals("Title 0", TrackerLibraryFilter.apply(entries, null, "", true).first().title)
    }

    @Test fun nativeStatusesMapToTheSameGroupsAcrossTrackers() {
        assertEquals("PLANNING", trackerStatus(ScrobblerService.MANGABAKA, "considering"))
        assertEquals("REPEATING", trackerStatus(ScrobblerService.MAL, "reading", true))
        assertEquals("REPEATING", trackerStatus(ScrobblerService.SHIKIMORI, "rewatching"))
        assertEquals("PAUSED", trackerStatus(ScrobblerService.KITSU, "on_hold"))
        assertEquals("REPEATING", trackerStatus(ScrobblerService.KITSU, "reconsuming"))
        assertEquals("CURRENT", trackerStatus(ScrobblerService.MAL, "reading"))
    }
}
