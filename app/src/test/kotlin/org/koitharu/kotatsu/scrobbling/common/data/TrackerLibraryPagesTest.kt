package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class TrackerLibraryPagesTest {
    @Test fun malLibraryKeepsRemoteOnlyEntriesNotesScoresAndRereading() {
        val page = malLibraryPage(JSONObject("""{"data":[{"node":{"id":15,"title":"Title","num_chapters":80,
            "alternative_titles":{"en":"English title","ja":"日本語","synonyms":["Alias"]}},
            "list_status":{"status":"reading","is_rereading":true,"score":9,"num_chapters_read":34,
            "comments":"Remote notes","updated_at":"2026-10-05T00:00:00Z"}}],"paging":{"next":"next"}}"""))
        val entry = page.entries.single()
        assertNull(entry.localMangaId); assertEquals("REPEATING", entry.status); assertEquals(34, entry.progress)
        assertEquals(9f, entry.score); assertEquals("Remote notes", entry.notes); assertTrue("日本語" in entry.aliases)
        assertTrue(page.hasNext)
    }

    @Test fun kitsuRelationshipsResolveIncludedMediaAndTwentyPointScores() {
        val page = kitsuLibraryPage(JSONObject("""{"data":[{"id":"22","relationships":{"manga":{"data":{"id":"15"}}},
            "attributes":{"status":"current","reconsuming":true,"progress":7,"ratingTwenty":17,"notes":null}}],
            "included":[{"id":"15","type":"manga","attributes":{"canonicalTitle":"A manga","slug":"a-manga",
            "chapterCount":50,"titles":{"en":"A manga","ja_jp":"漫画","en_jp":null}}}],"links":{"next":null}}"""))
        val entry = page.entries.single()
        assertEquals("REPEATING", entry.status); assertEquals(8.5f, entry.score); assertNull(entry.notes)
        assertEquals(setOf("A manga", "漫画"), entry.aliases); assertFalse(page.hasNext)
    }

    @Test fun missingKitsuIncludedMetadataIsAnErrorRatherThanAnEmptySuccess() {
        val payload = JSONObject("""{"data":[{"id":"22","relationships":{"manga":{"data":{"id":"15"}}},"attributes":{}}]}""")
        assertTrue(runCatching { kitsuLibraryPage(payload) }.isFailure)
    }

    @Test fun shikimoriMangaRatesPreserveRussianNamesAndText() {
        val entries = shikimoriLibraryPage(JSONArray("""[{"id":12,"status":"on_hold","chapters":4,"score":8,"text":"Notes",
            "manga":{"id":15,"name":"Title","russian":"Название","image":{"original":"/cover.png"},"url":"/mangas/15"}}]"""))
        assertEquals("PAUSED", entries.entries.single().status)
        assertEquals("https://shikimori.io/cover.png", entries.entries.single().coverUrl)
        assertEquals(setOf("Название"), entries.entries.single().aliases)
    }

    @Test fun mangaBakaBareLibraryRowsCanUseBatchedMetadata() {
        val payload = JSONObject("""{"data":[{"series_id":15,"state":"considering","rating":85,"progress_chapter":12.5,
            "note":"Remote","updated_at":"2026-10-05T00:00:00Z"}],"pagination":{"next":null}}""")
        val media = JSONObject("""{"id":15,"title":"A manga","native_title":"漫画","type":"Manga"}""")
        val entry = mangaBakaLibraryPage(payload, mapOf(15L to media)).entries.single()
        assertEquals("PLANNING", entry.status); assertEquals(8.5f, entry.score); assertEquals(12, entry.progress)
        assertEquals("Remote", entry.notes)
        assertTrue(runCatching { mangaBakaLibraryPage(payload, emptyMap()) }.isFailure)
    }

    @Test fun paginationCollectsAllPagesAndDeduplicatesAnOverlappingBoundary() = runTest {
        val offsets = mutableListOf<Int>()
        val result = collectTrackerLibraryPages { page, offset ->
            offsets += offset
            if (page == 1) TrackerLibraryPage(listOf(entry(1), entry(2)), 2, true)
            else TrackerLibraryPage(listOf(entry(2), entry(3)), 2, false)
        }
        assertEquals(listOf(1L, 2L, 3L), result.map { it.mediaId }); assertEquals(listOf(0, 2), offsets)
    }

    @Test fun paginationRejectsAnEmptyOrRepeatingContinuationWithoutReturningPartialData() = runTest {
        for (empty in listOf(false, true)) {
            val outcome = runCatching { collectTrackerLibraryPages { page, _ ->
                TrackerLibraryPage(if (empty && page > 1) emptyList() else listOf(entry(1)), if (empty && page > 1) 0 else 1, true)
            } }
            assertTrue(outcome.isFailure)
        }
    }

    @Test fun paginationPreservesCancellationAndDoesNotFetchAnotherPage() = runTest {
        var calls = 0
        val job = launch { collectTrackerLibraryPages { _, _ -> calls++; awaitCancellation() } }
        runCurrent(); job.cancelAndJoin(); assertEquals(1, calls)
    }

    private fun entry(id: Long) = TrackerLibraryEntry(ScrobblerService.ANILIST, id, id.toInt(), "Title $id", "", "",
        "CURRENT", 1, null, 7f, null, 100)
}
