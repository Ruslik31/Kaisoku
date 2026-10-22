package org.koitharu.kotatsu.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class PluginSourceLookupTest {
    @Test
    fun previousRequestsKeepTheirJarWhenSourceNamesOverlap() {
        val lookup = PluginSourceLookup()
        val old = PluginMangaSource(delegate(), "first.jar")
        lookup.replace(listOf(old))
        val replacement = PluginMangaSource(delegate(), "first.jar")
        val other = PluginMangaSource(delegate(), "other.jar")
        lookup.replace(listOf(other, replacement))
        assertSame(old, lookup.find(old.delegate))
        assertSame(replacement, lookup.find(replacement.delegate))
        assertSame(other, lookup.find(other.delegate))
        assertEquals(setOf(replacement, other), lookup.sources().toSet())
        assertNull(lookup.find(delegate()))
    }

    @Test
    fun removalKeepsOnlyLiveOldRequestsOutOfTheCurrentCatalog() {
        val lookup = PluginSourceLookup()
        val old = PluginMangaSource(delegate(), "old.jar")
        lookup.replace(listOf(old))
        lookup.replace(emptyList())
        assertSame(old, lookup.find(old.delegate))
        assertEquals(emptyList<PluginMangaSource>(), lookup.sources())
    }

    private fun delegate() = object : MangaSource {
        override val name = "SAME_SOURCE"
    }
}
