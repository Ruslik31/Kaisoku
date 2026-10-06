package org.koitharu.kotatsu.scrobbling

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.migrations.Migration31To32
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity

@RunWith(AndroidJUnit4::class)
class TrackerOwnershipTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MangaDatabase::class.java)

    @Test fun migrationBindsOnlyKnownAuthenticatedAccountsAndPreservesUnclaimedLinks() {
        val prefix = "tracker-migration-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        isolated.getSharedPreferences("ANILIST", Context.MODE_PRIVATE).edit()
            .putString("user", "123\nFixture\n\nANILIST").putString("access_token", "fixture-token").commit()
        val name = prefix + "db"
        migrations.createDatabase(name, 31).use { db ->
            db.execSQL("INSERT INTO scrobblings VALUES(2,12,-88,15,'CURRENT',7,'Notes',0.8)")
            db.execSQL("INSERT INTO scrobblings VALUES(3,15,-99,15,'reading',4,NULL,0.0)")
            db.execSQL("INSERT INTO scrobblings VALUES(2,16,-77,16,'CURRENT',3,NULL,0.0)")
            db.execSQL("INSERT INTO scrobblings VALUES(2,17,-77,17,'CURRENT',4,NULL,0.0)")
        }
        migrations.runMigrationsAndValidate(name, 32, true, Migration31To32(isolated)).use { db ->
            db.query("SELECT account_id,manga_id,chapter,comment FROM scrobblings WHERE scrobbler=2 AND manga_id=-88").use {
                assertTrue(it.moveToFirst()); assertEquals(123L, it.getLong(0)); assertEquals(-88L, it.getLong(1))
                assertEquals(7, it.getInt(2)); assertEquals("Notes", it.getString(3))
            }
            db.query("SELECT account_id,manga_id FROM scrobblings WHERE scrobbler=3").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertEquals(-99L, it.getLong(1))
            }
            db.query("SELECT COUNT(*),SUM(account_id) FROM scrobblings WHERE manga_id=-77").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertEquals(0L, it.getLong(1))
            }
        }
        isolated.getSharedPreferences("ANILIST", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun identicalMediaLinksCanCoexistForDifferentAccounts() = withDatabase { db ->
        val dao = db.getScrobblingDao()
        dao.replace(link(account = 1)); dao.replace(link(account = 2, chapter = 3))
        assertEquals(7, dao.find(2, -88, 1)?.chapter); assertEquals(3, dao.find(2, -88, 2)?.chapter)
        dao.delete(2, -88, 1)
        assertNull(dao.find(2, -88, 1)); assertNotNull(dao.find(2, -88, 2))
    }

    @Test fun favoriteDuplicatesDoNotUseAnotherAccountsTrackerMatch() = withDatabase { db ->
        val dao = db.getScrobblingDao()
        dao.replace(ScrobblingEntity(2, 12, -99, 15, "CURRENT", 7, null, 0f, 2))
        assertNull(dao.findMangaId(2, 15, -88, 1))
        assertEquals(-99L, dao.findMangaId(2, 15, -88, 2))
    }

    @Test fun manualRelinkingReplacesTheOldLinkAndRejectsItsLateResponses() = withDatabase { db ->
        val dao = db.getScrobblingDao(); val old = link()
        dao.replace(old); dao.replace(link(target = 20, rate = 22, chapter = 2))
        dao.updateLinked(old); dao.refreshLinked(old, expectedRateId = 12)
        assertEquals(20L, dao.find(2, -88, 1)?.targetId)
        assertEquals(1, dao.findForManga(-88).size)
    }

    @Test fun recreatingTheSameRemoteTitleCanRefreshItsListEntryId() = withDatabase { db ->
        val dao = db.getScrobblingDao(); dao.replace(link())
        dao.refreshLinked(link(rate = 99, chapter = 10), expectedRateId = 12)
        assertEquals(99, dao.find(2, -88, 1)?.id); assertEquals(1, dao.findForManga(-88).size)
    }

    @Test fun remoteRefreshNeverMovesReaderHistoryOrFavoriteCategories() = withDatabase { db ->
        db.getMangaDao().upsert(MangaEntity(-88, "Local title", null, "/title", "https://example.org/title", 0.5f,
            false, "SAFE", "", null, null, null, "fixture"))
        val history = HistoryEntity(-88, 1, 2, 777, 3, 5555f, 0.34f, 0, 50)
        db.getHistoryDao().upsert(history)
        db.getFavouriteCategoriesDao().upsert(FavouriteCategoryEntity(1, 1, 1, "My category", "NEWEST", true, true, 0))
        db.getFavouritesDao().upsert(FavouriteEntity(-88, 1, 1, false, 1, 0))
        val sql = db.openHelper.writableDatabase
        fun snapshot(table: String) = sql.query("SELECT * FROM $table").use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) }) }
        }
        val before = listOf(snapshot("history"), snapshot("favourites"), snapshot("favourite_categories"), snapshot("manga"))
        val dao = db.getScrobblingDao(); dao.replace(link())
        dao.refreshLinked(ScrobblingEntity(2, 12, -88, 15, "PAUSED", 40, "Website notes", 0.95f, 1), 12)
        assertEquals(before, listOf(snapshot("history"), snapshot("favourites"), snapshot("favourite_categories"), snapshot("manga")))
        assertEquals(40, dao.find(2, -88, 1)?.chapter)
    }

    private fun link(account: Long = 1, target: Long = 15, rate: Int = 12, chapter: Int = 7) =
        ScrobblingEntity(2, rate, -88, target, "CURRENT", chapter, "Notes", 0.8f, account)

    private fun withDatabase(block: suspend (MangaDatabase) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
