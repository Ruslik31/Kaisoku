package org.koitharu.kotatsu.history.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase

@RunWith(AndroidJUnit4::class)
class NovelPositionSaveTest {
    @Test fun sameChapterSavesChangeOnlyPositionAndKeepOriginalCreationDate() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MangaDatabase::class.java).build()
        try {
            db.openHelper.writableDatabase.execSQL("""INSERT INTO manga (manga_id,title,alt_title,url,public_url,rating,nsfw,
                content_rating,cover_url,large_cover_url,state,author,source)
                VALUES (1,'Novel','Alias','/1','https://example.org/1',-1,0,NULL,'',NULL,NULL,'Author','REMANGA')""")
            val dao = db.getHistoryDao()
            dao.insert(HistoryEntity(1, 10, 20, 7, 1, 10f, 0.1f, 0, 12))
            assertEquals(1, dao.updatePosition(1, 7, 12, 9, 9000f, 0.75f, 100))
            val row = dao.findForRestore(1)!!
            assertEquals(10L, row.createdAt); assertEquals(100L, row.updatedAt)
            assertEquals(9, row.page); assertEquals(0.75f, row.percent)
            assertEquals(0, dao.updatePosition(1, 8, 12, 0, 0f, 0f, 101))
            assertEquals(0, dao.updatePosition(1, 7, 13, 0, 0f, 0f, 101))
            assertEquals(9, dao.findForRestore(1)!!.page)
            dao.delete(1)
            assertEquals(0, dao.updatePosition(1, 7, 12, 0, 0f, 0f, 101))
            assertTrue(dao.findForRestore(1)!!.deletedAt > 0)
            db.openHelper.readableDatabase.query("SELECT title, author FROM manga WHERE manga_id=1").use {
                assertTrue(it.moveToFirst()); assertEquals("Novel", it.getString(0)); assertEquals("Author", it.getString(1))
            }
        } finally { db.close() }
    }
}
