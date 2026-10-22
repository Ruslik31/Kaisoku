package org.koitharu.kotatsu.core.parser

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class SavedMetadataMergeTest {
    private val source = MangaParserSource.MANGALIB
    private val chapter = MangaChapter(20, "One", 1f, 0, "/chapter/1", null, 100, null, source)
    private val seed = Manga(10, "Title", emptySet(), "/title", "https://example.org/title", 0.8f,
        null, "https://example.org/cover", emptySet(), null, emptySet(), source = source)

    @Test fun unknownAndKnownEmptyChaptersRemainDistinct() {
        assertNull(mergeSavedMetadata(seed.copy(chapters = null), null, null).chapters)
        assertEquals(emptyList<MangaChapter>(), mergeSavedMetadata(seed.copy(chapters = emptyList()), null, null).chapters)
    }

    @Test fun savedNonemptyChaptersWinOverIncompleteSeedWithoutRewritingIdentities() {
        val saved = seed.copy(title = "Saved title", url = "/saved-title", publicUrl = "https://old.example/title",
            chapters = listOf(chapter))
        val result = mergeSavedMetadata(seed.copy(chapters = emptyList()), saved, null)
        assertEquals(seed.id, result.id)
        assertEquals(seed.url, result.url)
        assertEquals(seed.publicUrl, result.publicUrl)
        assertSame(source, result.source)
        assertEquals(listOf(chapter), result.chapters)
        assertEquals("Saved title", result.title)
    }

    @Test fun missingDatabaseChaptersRetainTheKnownSeedList() {
        val result = mergeSavedMetadata(seed.copy(chapters = listOf(chapter)), seed.copy(chapters = null), null)
        assertEquals(listOf(chapter), result.chapters)
    }

    @Test fun blankSavedFieldsAreFilledFromTheSeedBeforeCompatibleArchiveMetadata() {
        val stored = seed.copy(title = "", coverUrl = "", largeCoverUrl = "", description = " ")
        val archive = seed.copy(description = "Archived description", coverUrl = "archive cover", largeCoverUrl = "large")
        val result = mergeSavedMetadata(seed, stored, archive)
        assertEquals(seed.title, result.title)
        assertEquals(seed.coverUrl, result.coverUrl)
        assertEquals("large", result.largeCoverUrl)
        assertEquals("Archived description", result.description)
    }

    @Test fun embeddedRemoteChaptersAreUsedOnlyForACompatibleIdentity() {
        val archive = seed.copy(chapters = listOf(chapter), description = "Saved description")
        assertEquals(listOf(chapter), mergeSavedMetadata(seed, null, archive).chapters)
        val unrelated = archive.copy(id = 99, url = "/other", publicUrl = "https://example.org/other")
        assertNull(mergeSavedMetadata(seed, null, unrelated).chapters)
        assertNull(mergeSavedMetadata(seed, null, unrelated).description)
        assertNull(mergeSavedMetadata(seed, null, archive.copy(source = MangaParserSource.REMANGA)).chapters)
    }

    @Test fun aKnownEmptySeedIsNotChangedByAnOlderArchive() {
        val result = mergeSavedMetadata(seed.copy(chapters = emptyList()), null, seed.copy(chapters = listOf(chapter)))
        assertEquals(emptyList<MangaChapter>(), result.chapters)
    }

    @Test fun storedRowsForAnotherSourceAreIgnored() {
        val stored = seed.copy(source = MangaParserSource.REMANGA, chapters = listOf(chapter), title = "Other")
        assertEquals(seed, mergeSavedMetadata(seed, stored, null))
    }
}
