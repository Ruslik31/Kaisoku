package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.hasChapterText
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.UnresolvedLnReaderSource
import java.io.IOException

class MihonNovelSupportTest {
    @Test fun chapterUrlIsPassedDirectlyToTheTextCapability() = runTest {
        var received: Page? = null
        val source = object : NovelSource {
            override suspend fun fetchPageText(page: Page): String {
                received = page
                return "<p>Chapter text</p><img src='../images/1.png'>"
            }
        }
        val html = fetchMihonChapterHtml(source, "/book/42/chapter", "https://fixture.invalid/book/42/chapter")
        assertEquals(0, received!!.index)
        assertEquals("/book/42/chapter", received!!.url)
        assertNull(received!!.imageUrl)
        assertTrue(html.contains("Chapter text"))
        assertTrue(html.contains("https://fixture.invalid/book/images/1.png"))
    }

    @Test fun blankTextFailureAndCancellationAreNotHidden() = runTest {
        for (raw in listOf("", " \n\t")) {
            assertThrows(IOException::class.java) { normalizeMihonChapterHtml(raw, "https://fixture.invalid/") }
        }
        val failure = IOException("Fixture failure")
        val source = object : NovelSource {
            var cancellation = false
            override suspend fun fetchPageText(page: Page): String {
                if (cancellation) throw CancellationException("Cancelled fixture")
                throw failure
            }
        }
        assertSame(failure, runCatching { fetchMihonChapterHtml(source, "/chapter", "") }.exceptionOrNull())
        source.cancellation = true
        assertTrue(runCatching { fetchMihonChapterHtml(source, "/chapter", "") }.exceptionOrNull() is CancellationException)
    }

    @Test fun sourceIdentityIsIndependentOfTextAndSettingsCapabilities() {
        val manga = MihonMangaSource("fixture.novel", 42)
        val novel = MihonMangaSource("fixture.novel", 42, isNovelSource = true, isConfigurable = true)
        val settings = MihonMangaSource("fixture.novel", 43, isNovelSource = true, isConfigurable = true, isCatalogue = false)
        assertEquals(manga, novel)
        assertEquals(manga.name, novel.name)
        assertEquals(manga.hashCode(), novel.hashCode())
        assertTrue(novel.hasChapterText())
        assertFalse(manga.hasChapterText())
        assertFalse(settings.hasChapterText())
        assertTrue(LnReaderMangaSource("fixture", "Fixture").hasChapterText())
        assertTrue(UnresolvedLnReaderSource("missing-fixture").hasChapterText())
    }

    @Test fun textOnlyMihonAndLnReaderDetailsNeverRequestImagePageThumbnails() {
        fun supports(source: org.koitharu.kotatsu.parsers.model.MangaSource): Boolean {
            val manga = org.koitharu.kotatsu.parsers.model.Manga(id = 431, title = "Fixture", altTitles = emptySet(),
                url = "/fixture", publicUrl = "https://fixture.invalid/fixture", rating = -1f, contentRating = null,
                coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), source = source)
            return org.koitharu.kotatsu.details.data.MangaDetails(manga).supportsPageThumbnails
        }
        assertFalse(supports(MihonMangaSource("fixture.thumbnail", 731, isNovelSource = true)))
        assertTrue(supports(MihonMangaSource("fixture.thumbnail", 732)))
        assertFalse(supports(LnReaderMangaSource("fixture-thumbnail", "Fixture")))
        assertFalse(supports(UnresolvedLnReaderSource("missing-thumbnail")))
        assertTrue(supports(org.koitharu.kotatsu.parsers.model.MangaParserSource.MANGADEX))
    }

    @Test fun onlyInspectedOptionalRuntimeNamespacesCanFallBackToTheirApk() {
        assertTrue(allowsOptionalExtensionClass("eu.kanade.tachiyomi.source.online.ParsedNovelSource"))
        assertTrue(allowsOptionalExtensionClass("kotlinx.datetime.LocalDate"))
        assertTrue(allowsOptionalExtensionClass("kotlinx.io.Buffer"))
        for (shared in listOf("eu.kanade.tachiyomi.source.Source", "eu.kanade.tachiyomi.source.model.Page",
            "eu.kanade.tachiyomi.network.NetworkHelper", "kotlinx.coroutines.Job", "kotlinx.serialization.KSerializer",
            "androidx.preference.Preference", "kotlin.collections.List")) {
            assertFalse(shared, allowsOptionalExtensionClass(shared))
        }
    }
}
