package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-and-shape assertions for the TachiyomiX 1.6 stub surface added to `eu.kanade.tachiyomi.*`.
 * Does not run an extension; confirms the host's 1.4/1.5 stubs now carry the v1.6 additions with
 * the exact JVM signatures the extensions' dex bytecode will resolve at runtime.
 */
class MihonStub16ContractTest {

    @Test
    fun sourceHasMangaUpdateMethod() {
        val cls = Class.forName("eu.kanade.tachiyomi.source.Source")
        val methods = cls.methods.map { it.name }
        assertTrue("Source.getPopularManga present", "getPopularManga" in methods)
        assertTrue("Source.getLatestUpdates present", "getLatestUpdates" in methods)
        assertTrue("Source.getSearchManga present", "getSearchManga" in methods)
        assertTrue("Source.getPageList present", "getPageList" in methods)
        assertTrue("Source.getMangaUpdate present", "getMangaUpdate" in methods)
        assertTrue("Source.getFilterList present", "getFilterList" in methods)
    }

    @Test
    fun catalogueSourceKeepsFilterListOverride() {
        val cls = Class.forName("eu.kanade.tachiyomi.source.CatalogueSource")
        val method = cls.methods.first { it.name == "getFilterList" }
        assertNotNull(method)
    }

    @Test
    fun httpSourceHasHomeUrl() {
        val cls = Class.forName("eu.kanade.tachiyomi.source.online.HttpSource")
        val method = cls.methods.firstOrNull { it.name == "getHomeUrl" }
        assertNotNull(method)
    }

    @Test
    fun sMangaUpdateClassExists() {
        val cls = Class.forName("eu.kanade.tachiyomi.source.model.SMangaUpdate")
        assertNotNull(cls)
        assertEquals(SMangaUpdate::class.java.name, cls.name)
    }

    @Test
    fun smangaHasMemoOfJsonObject() {
        val cls = Class.forName("eu.kanade.tachiyomi.source.model.SManga")
        val method = cls.methods.firstOrNull { it.name == "getMemo" || it.name == "getMemo\$bridge" }
        assertNotNull("SManga.getMemo present", method)
        assertEquals(JsonObject::class.java, method!!.returnType)
    }

    @Test
    fun schapterHasMemoOfJsonObject() {
        val cls = Class.forName("eu.kanade.tachiyomi.source.model.SChapter")
        val method = cls.methods.firstOrNull { it.name == "getMemo" || it.name == "getMemo\$bridge" }
        assertNotNull("SChapter.getMemo present", method)
        assertEquals(JsonObject::class.java, method!!.returnType)
    }

    @Test
    fun smangaImplCarriesMemoField() {
        val instance = SManga.create()
        assertNotNull(instance.memo)
        assertTrue(instance.memo is JsonObject)
    }

    @Test
    fun schapterImplCarriesMemoField() {
        val instance = SChapter.create()
        assertNotNull(instance.memo)
        assertTrue(instance.memo is JsonObject)
    }

    @Test
    fun mangaUpdateConstructs() {
        val manga = SManga.create().also { it.url = "/manga/x" }
        val chapter = SChapter.create().also {
            it.url = "/manga/x/c1"
            it.name = "c1"
        }
        val update = SMangaUpdate(manga = manga, chapters = listOf(chapter))
        assertEquals("/manga/x", update.manga.url)
        assertEquals(1, update.chapters.size)
    }
}
