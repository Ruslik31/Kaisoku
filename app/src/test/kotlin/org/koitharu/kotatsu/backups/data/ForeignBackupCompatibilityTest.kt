package org.koitharu.kotatsu.backups.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.SourceBackup
import java.util.zip.ZipInputStream

/** Public issue #53 attachments, irreversibly sanitized; uses actual native backup DTOs, not an Android DB. */
class ForeignBackupCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        useAlternativeNames = false
    }

    @Test fun bothForeignExportsDecodeAllLibraryRecordsAndPreserveReferencesAndProgress() {
        for (name in listOf("kotatsu.bk.zip", "futon.bk.zip")) {
            val sections = read(name)
            assertFalse(sections.containsKey("settings"))
            val history = json.decodeFromString<List<HistoryBackup>>(sections.getValue("history"))
            val favorites = json.decodeFromString<List<FavouriteBackup>>(sections.getValue("favourites"))
            val categories = json.decodeFromString<List<CategoryBackup>>(sections.getValue("categories"))
            val sources = json.decodeFromString<List<SourceBackup>>(sections.getValue("sources"))
            assertEquals(81, history.size)
            assertEquals(97, favorites.size)
            assertEquals(2, categories.size)
            assertEquals(if (name.startsWith("kotatsu")) 382 else 410, sources.size)
            assertEquals(83, favorites.count { it.manga.isNsfw })
            assertTrue(history.all { it.mangaId == it.manga.id && it.deletedAt == 0L })
            assertTrue(favorites.all { f -> f.mangaId == f.manga.id && f.deletedAt == 0L &&
                categories.any { it.categoryId.toLong() == f.categoryId } })
            assertTrue((history.map { it.manga.source } + favorites.map { it.manga.source }).all { source ->
                sources.any { it.source == source }
            })
            assertTrue(history.all { it.scroll.isFinite() && it.percent.isFinite() })
            val exportedHistory = json.decodeFromString<List<HistoryBackup>>(json.encodeToString(history))
            assertEquals(history.map { it.toEntity() }, exportedHistory.map { it.toEntity() })
            val exportedFavorites = json.decodeFromString<List<FavouriteBackup>>(json.encodeToString(favorites))
            assertEquals(favorites.map { it.toEntity() }, exportedFavorites.map { it.toEntity() })
            assertEquals(favorites.map { it.manga.toEntity() }, exportedFavorites.map { it.manga.toEntity() })
        }
    }

    @Test fun reexportedForeignBackupKeepsTheSameRecordsDespiteTagSetOrdering() {
        val old = read("kotatsu.bk.zip")
        val other = read("futon.bk.zip")
        val historyA = json.decodeFromString<List<HistoryBackup>>(old.getValue("history")).associateBy { it.mangaId }
        val historyB = json.decodeFromString<List<HistoryBackup>>(other.getValue("history")).associateBy { it.mangaId }
        assertEquals(historyA.keys, historyB.keys)
        for (id in historyA.keys) {
            assertEquals(historyA.getValue(id).toEntity(), historyB.getValue(id).toEntity())
            assertEquals(historyA.getValue(id).manga.toEntity(), historyB.getValue(id).manga.toEntity())
            assertEquals(historyA.getValue(id).manga.tags.map { it.toEntity() }.toSet(),
                historyB.getValue(id).manga.tags.map { it.toEntity() }.toSet())
        }
        val favoritesA = json.decodeFromString<List<FavouriteBackup>>(old.getValue("favourites"))
        val favoritesB = json.decodeFromString<List<FavouriteBackup>>(other.getValue("favourites"))
        assertEquals(favoritesA.map { it.toEntity() }.toSet(), favoritesB.map { it.toEntity() }.toSet())
        val sourcesA = json.decodeFromString<List<SourceBackup>>(old.getValue("sources")).map { it.source }.toSet()
        val sourcesB = json.decodeFromString<List<SourceBackup>>(other.getValue("sources")).map { it.source }.toSet()
        assertTrue(sourcesB.containsAll(sourcesA))
        assertEquals(28, (sourcesB - sourcesA).size)
    }

    private fun read(name: String): Map<String, String> {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("foreign-backups/$name"))
        return ZipInputStream(stream).use { zip ->
            buildMap {
                var entry = zip.nextEntry
                while (entry != null) {
                    require(!entry.isDirectory && !entry.name.contains('/'))
                    put(entry.name, zip.readBytes().decodeToString())
                    entry = zip.nextEntry
                }
            }
        }
    }
}
