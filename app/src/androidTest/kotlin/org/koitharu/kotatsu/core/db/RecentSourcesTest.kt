package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity

@RunWith(AndroidJUnit4::class)
class RecentSourcesTest {
    @Test
    fun onlyUsedEnabledSourcesFillRecentSlotsWithoutChangingSourceRecords() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MangaDatabase::class.java,
        ).build()
        try {
            val dao = db.getSourcesDao()
            fun source(name: String, enabled: Boolean, usedAt: Long) =
                MangaSourceEntity(name, enabled, 0, 0, usedAt, false, 0)
            val rows = listOf(
                source("SAFE_UNUSED", true, 0),
                source("ADULT_UNUSED", false, 0),
                source("RECENT_DISABLED", false, 300),
                source("MIHON_TEST", true, 200),
                source("lnreader:TEST", true, 100),
            )
            rows.forEach { dao.upsert(it) }
            assertEquals(listOf("MIHON_TEST", "lnreader:TEST"), dao.findLastUsed(2, true).map { it.source })
            assertEquals(listOf("RECENT_DISABLED", "MIHON_TEST"), dao.findLastUsed(2, false).map { it.source })
            assertTrue(dao.findLastUsed(0, true).isEmpty())
            assertEquals(rows.sortedBy { it.source }, dao.findAll().sortedBy { it.source })
            rows.forEach { dao.setLastUsed(it.source, 0) }
            assertTrue(dao.findLastUsed(2, false).isEmpty())
        } finally {
            db.close()
        }
    }
}
