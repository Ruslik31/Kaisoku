package org.koitharu.kotatsu.backups.data

import android.content.Context
import android.content.ContextWrapper
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MetadataRefreshSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.reader.data.TapGridSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Every preference and database mutation is confined to unique test stores. */
@HiltAndroidTest
class SavedMetadataBackupTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var sources: MangaSourcesRepository
    @Inject lateinit var filters: SavedFiltersRepository

    @Before fun inject() = hilt.inject()

    @Test fun nativeSettingsBackupPreservesDynamicTrueFalseAndUnavailableFlags() = runBlocking {
        val context = isolatedContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().useDatabase { database ->
            try {
                val metadata = MetadataRefreshSettings(context)
                val repository = repository(context, database)
                metadata.setSavedMetadataOnly(10, true)
                metadata.setSavedMetadataOnly(-20, false)
                metadata.useSavedMetadataForUnavailableSources = true
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { repository.createBackup(it, null, setOf(BackupSection.SETTINGS)) }
                prefs.edit().clear().commit()
                assertFalse(metadata.isSavedMetadataOnly(10))
                val result = repository.restoreBackup(ZipInputStream(ByteArrayInputStream(output.toByteArray())),
                    setOf(BackupSection.SETTINGS), null)
                assertTrue(result.failures.toString(), result.isAllSuccess)
                assertTrue(metadata.isSavedMetadataOnly(10))
                assertFalse(metadata.isSavedMetadataOnly(-20))
                assertTrue(prefs.contains(MetadataRefreshSettings.titleKey(-20)))
                assertTrue(metadata.useSavedMetadataForUnavailableSources)
                val oldBackup = archive(BackupSection.SETTINGS, "[{\"theme\":\"dark\"}]")
                assertTrue(repository.restoreBackup(ZipInputStream(ByteArrayInputStream(oldBackup)),
                    setOf(BackupSection.SETTINGS), null, isMerge = true).isAllSuccess)
                assertTrue(metadata.isSavedMetadataOnly(10))
                assertTrue(prefs.contains(MetadataRefreshSettings.titleKey(-20)))
            } finally {
                prefs.edit().clear().commit()
                context.getSharedPreferences("reader_tap_grid", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    @Test fun backupDuplicateMergeCopiesActualCanonicalFlagsAfterCommitAndPreservesExplicitTargets() = runBlocking {
        val context = isolatedContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().useDatabase { database ->
            try {
                for ((id, path) in listOf(10L to "/one", 20L to "/one", 30L to "/two", 40L to "/two")) {
                    database.getMangaDao().upsert(Manga(id, "Fixture", emptySet(), path, "https://example.org$path",
                        -1f, null, null, emptySet(), null, emptySet(), source = MangaParserSource.MANGALIB).toEntity(), emptyList())
                }
                val metadata = MetadataRefreshSettings(context)
                metadata.setSavedMetadataOnly(20, true)
                metadata.setSavedMetadataOnly(10, false)
                metadata.setSavedMetadataOnly(40, false)
                val input = archive(BackupSection.MANGA_PREFERENCES, "[]")
                val result = repository(context, database).restoreBackup(ZipInputStream(ByteArrayInputStream(input)),
                    setOf(BackupSection.MANGA_PREFERENCES), null, isMerge = true)
                assertTrue(result.failures.toString(), result.isAllSuccess)
                assertNotNull(database.getMangaDao().find(10))
                assertNull(database.getMangaDao().find(20))
                assertNotNull(database.getMangaDao().find(30))
                assertNull(database.getMangaDao().find(40))
                assertFalse(metadata.isSavedMetadataOnly(10))
                assertFalse(metadata.isSavedMetadataOnly(30))
                assertTrue(prefs.contains(MetadataRefreshSettings.titleKey(30)))
                assertTrue(metadata.isSavedMetadataOnly(20))
                assertTrue(prefs.contains(MetadataRefreshSettings.titleKey(40)))
            } finally { prefs.edit().clear().commit() }
        }
    }

    private inline fun <T> MangaDatabase.useDatabase(block: (MangaDatabase) -> T): T =
        try { block(this) } finally { close() }

    private fun repository(context: Context, database: MangaDatabase) = BackupRepository(
        context, database, AppSettings(context), TapGridSettings(context), sources, filters,
        MetadataRefreshSettings(context),
    )

    private fun archive(section: BackupSection, json: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output ->
            output.putNextEntry(ZipEntry(section.entryName))
            output.write(json.toByteArray())
            output.closeEntry()
        }
    }.toByteArray()

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "saved-metadata-backup-${UUID.randomUUID()}-"
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
    }
}
