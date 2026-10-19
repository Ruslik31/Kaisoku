package org.koitharu.kotatsu.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterWebUrlTest {
    @Test fun absoluteLinksKeepTheirChapterPathQueryAndFragment() {
        assertEquals("https://source.invalid/read/42?lang=en#page-3",
            chapterWebUrl("https://source.invalid/read/42?lang=en#page-3"))
        assertEquals("http://source.invalid:8080/chapter/2", chapterWebUrl("http://source.invalid:8080/chapter/2"))
        assertEquals("https://other.invalid/chapter/2",
            resolveChapterWebUrl("configured.invalid", "https://other.invalid/chapter/2"))
    }

    @Test fun nativePathsUseTheConfiguredMirror() {
        assertEquals("https://mirror.invalid/chapter/42", resolveChapterWebUrl("mirror.invalid", "/chapter/42"))
        assertEquals("http://mirror.invalid:8080/chapter/42",
            resolveChapterWebUrl("http://mirror.invalid:8080", "chapter/42"))
        assertEquals("https://mirror.invalid/chapter.html?id=42",
            resolveChapterWebUrl("mirror.invalid", "chapter.html?id=42"))
        assertEquals("https://cdn.invalid/read/2", resolveChapterWebUrl("mirror.invalid", "//cdn.invalid/read/2"))
    }

    @Test fun opaqueIdentifiersAndEmptyPathsHaveNoGuessedWebsiteRoute() {
        for (path in listOf("", "42", "2.0", "chapter-id", UUID, "/", "?id=42", "#chapter")) {
            assertNull(path, resolveChapterWebUrl("source.invalid", path))
        }
        assertNull(resolveChapterWebUrl("", "/chapter/42"))
    }

    @Test fun onlyMangaDexHasTheKnownUuidChapterRoute() {
        assertEquals("https://mirror.invalid/chapter/$UUID", resolveChapterWebUrl("mirror.invalid", UUID, mangaDex = true))
        assertNull(resolveChapterWebUrl("mirror.invalid", "1234", mangaDex = true))
        assertNull(resolveChapterWebUrl("mirror.invalid", UUID))
    }

    @Test fun localScriptCredentialsAndControlCharactersCannotReachTheBrowser() {
        for (url in listOf("file:///book/chapter", "content://book/chapter", "data:text/html,chapter",
            "javascript:alert(1)", "https://user:secret@source.invalid/chapter/2", "https://user@source.invalid/chapter/2",
            "https://@source.invalid/chapter/2",
            "https://source.invalid/chapter/2\n", "https://source.invalid/chap\tter", " https://source.invalid/chapter/2",
            "https://source.invalid/chap\u0085ter", "https://source.invalid/chap\u007fter",
            "https://source.invalid\\chapter\\2", "https:source.invalid/chapter/2", "//source.invalid/chapter/2")) {
            assertNull(url, chapterWebUrl(url))
        }
        for (path in listOf("file:///book/2", "javascript:chapter/2", "//user:secret@source.invalid/read/2", "/read/\n2")) {
            assertNull(path, resolveChapterWebUrl("source.invalid", path))
        }
        assertNull(resolveChapterWebUrl("https://user:secret@source.invalid", "/read/2"))
    }

    @Test fun lnReaderRemovesOnlyItsTitleContextSeparator() {
        assertEquals("/read/book/2?lang=ru", lnReaderChapterWebPath("/novel/book|||/read/book/2?lang=ru"))
        assertEquals("https://source.invalid/read/2", lnReaderChapterWebPath("https://source.invalid/read/2"))
        assertEquals("https://source.invalid/read/book/2",
            resolveChapterWebUrl("https://source.invalid", lnReaderChapterWebPath("/novel/book|||/read/book/2")))
        assertNull(resolveChapterWebUrl("source.invalid", lnReaderChapterWebPath("book|||42")))
    }

    private companion object {
        const val UUID = "0a1b2c3d-1234-5678-9abc-0123456789ab"
    }
}
