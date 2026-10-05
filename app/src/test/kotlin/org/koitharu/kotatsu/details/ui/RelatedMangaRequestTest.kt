package org.koitharu.kotatsu.details.ui

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag

class RelatedMangaRequestTest {
    private val source = MangaParserSource.READMANGA_RU
    private val manga = Manga(7, "Title", emptySet(), "/title", "https://readmanga.ru/title", 0f,
        null, null, emptySet(), null, emptySet(), null, null, null, source)

    @Test fun initialStubWaitsForLoadedMetadataButOfflineCompletionHasFallback() {
        val stub = MangaDetails(manga)
        assertNull(relatedMangaRequest(stub, false, true, true))
        assertNotNull(relatedMangaRequest(stub.copy(isLoaded = true), false, true, true))
        assertNotNull(relatedMangaRequest(stub, true, true, true))
        assertNull(relatedMangaRequest(stub, true, false, true))
    }

    @Test fun changingTagsOrAdultSettingsRefreshesButDownloadsAndProgressDoNot() {
        val first = relatedMangaRequest(MangaDetails(manga), true, true, true)!!
        val downloaded = manga.copy(chapters = listOf(MangaChapter(9, "Downloaded", 1f, 0,
            "/chapter", null, 0, null, source)))
        assertEquals(first.key, relatedMangaRequest(MangaDetails(downloaded), true, true, true)!!.key)
        assertNotEquals(first.key, relatedMangaRequest(MangaDetails(manga.copy(tags = setOf(
            MangaTag("Fantasy", "fantasy", source)))), true, true, true)!!.key)
        assertNotEquals(first.key, relatedMangaRequest(MangaDetails(manga), true, true, false)!!.key)
    }
}
