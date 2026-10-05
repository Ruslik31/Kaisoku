package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Test

class MihonSourceRegistryTest {
    @Test fun newSessionDropsOldPageStateAndRejectsLateCallbacks() {
        val old = MihonMangaSource("registry-test", 1)
        val replacement = MihonMangaSource("registry-test", 1)
        val url = "https://images.test/page"
        val oldPage = Page(0, "old", "old-image")
        val headers = Headers.headersOf("Referer", "https://old.test/")
        try {
            val oldInstance = Any()
            MihonSourceRegistry.register(oldInstance, old, "https://old.test/")
            MihonSourceRegistry.rememberPage(old, url, oldPage)
            MihonSourceRegistry.rememberPageHeaders(old, url, headers)
            assertSame(oldPage, MihonSourceRegistry.getPage(old, url))
            MihonSourceRegistry.register(Any(), replacement, null)
            assertNull(MihonSourceRegistry.findSource(oldInstance))
            assertNull(MihonSourceRegistry.getPage(replacement, url))
            assertNull(MihonSourceRegistry.getPageHeaders(replacement, url))
            assertNull(MihonSourceRegistry.getDefaultReferer(replacement))
            MihonSourceRegistry.rememberPage(old, url, oldPage)
            MihonSourceRegistry.rememberPageHeaders(old, url, headers)
            assertNull(MihonSourceRegistry.getPage(replacement, url))
            val newPage = Page(0, "new", "new-image")
            MihonSourceRegistry.rememberPage(replacement, url, newPage)
            assertSame(newPage, MihonSourceRegistry.getPage(old, url))
            // Lookup remains name-based for pages restored from backups; writes are session-bound.
        } finally {
            MihonSourceRegistry.retainSources(emptySet())
        }
    }

    @Test fun removalDropsDefinitionsPagesAndHeadersWithoutChangingSourceIdentity() {
        val source = MihonMangaSource("registry-removal-test", 2)
        val name = source.name
        val instance = Any()
        MihonSourceRegistry.register(instance, source, "https://source.test/")
        MihonSourceRegistry.rememberPage(source, "page", Page(0))
        MihonSourceRegistry.retainSources(emptySet())
        assertNull(MihonSourceRegistry.resolveSource(name))
        assertNull(MihonSourceRegistry.findSource(instance))
        assertNull(MihonSourceRegistry.getPage(source, "page"))
        MihonSourceRegistry.rememberPage(source, "page", Page(0))
        assertNull(MihonSourceRegistry.getPage(source, "page"))
        assertEquals(name, source.name)
    }
}
