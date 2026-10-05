package org.koitharu.kotatsu.sync.drive

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase

@RunWith(AndroidJUnit4::class)
class DriveReplicaJournalTest {
    @Test fun disabledBackendDoesNotJournalOrdinarySourceOrCategoryWrites() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = false)
        insertSource(db)
        db.execSQL("UPDATE sources SET enabled=1 WHERE source='test'")
        assertEquals(0L, count(db))
        assertEquals(0L, scalar(db, "SELECT clock FROM ${DriveReplicaJournal.STATE}"))
    }

    @Test fun explicitSourceDisableBeforeFirstSyncProducesDeletionEvent() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = true)
        insertSource(db)
        db.execSQL("UPDATE sources SET enabled=1 WHERE source='test'")
        val before = scalar(db, "SELECT version FROM ${DriveReplicaJournal.CHANGES} WHERE section='sources'")
        db.execSQL("UPDATE sources SET enabled=0 WHERE source='test'")
        assertEquals(1L, scalar(db, "SELECT deleted FROM ${DriveReplicaJournal.CHANGES} WHERE section='sources'"))
        assertTrue(scalar(db, "SELECT version FROM ${DriveReplicaJournal.CHANGES} WHERE section='sources'") > before)
    }

    @Test fun ordinarySourceUsageDoesNotGenerateAnotherVersion() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = true)
        insertSource(db)
        val before = scalar(db, "SELECT clock FROM ${DriveReplicaJournal.STATE}")
        db.execSQL("UPDATE sources SET used_at=123 WHERE source='test'")
        assertEquals(before, scalar(db, "SELECT clock FROM ${DriveReplicaJournal.STATE}"))
    }

    @Test fun remoteApplyMuteDoesNotEchoRemoteRowsAsLocalEdits() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = true)
        db.execSQL("UPDATE ${DriveReplicaJournal.STATE} SET muted=1")
        insertSource(db)
        db.execSQL("UPDATE sources SET enabled=1 WHERE source='test'")
        assertEquals(0L, count(db))
        db.execSQL("UPDATE ${DriveReplicaJournal.STATE} SET muted=0")
        db.execSQL("DELETE FROM sources WHERE source='test'")
        assertEquals(1L, count(db))
        assertEquals(1L, scalar(db, "SELECT deleted FROM ${DriveReplicaJournal.CHANGES}"))
    }

    @Test fun newlyCreatedCategoriesRetainTheirUidAcrossRenameAndDelete() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = true)
        db.execSQL("INSERT INTO favourite_categories(created_at,sort_key,title,`order`,track,show_in_lib,deleted_at) VALUES(1,1,'Reading','NEWEST',1,1,0)")
        val uid = db.query("SELECT uid FROM ${DriveReplicaJournal.CATEGORIES}").use { it.moveToFirst(); it.getString(0) }
        db.execSQL("UPDATE favourite_categories SET title='Renamed'")
        db.execSQL("DELETE FROM favourite_categories")
        assertEquals(uid, db.query("SELECT uid FROM ${DriveReplicaJournal.CATEGORIES}").use { it.moveToFirst(); it.getString(0) })
        assertEquals(1L, scalar(db, "SELECT deleted FROM ${DriveReplicaJournal.CHANGES} WHERE section='categories'"))
    }

    @Test fun transactionRollbackRollsBackJournalAndClockTogether() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = true)
        db.beginTransaction()
        try { insertSource(db) } finally { db.endTransaction() }
        assertEquals(0L, count(db))
        assertEquals(0L, scalar(db, "SELECT clock FROM ${DriveReplicaJournal.STATE}"))
    }

    @Test fun observedRemoteClockForcesLaterLocalEditsAboveIt() = withDatabase { db ->
        DriveReplicaJournal.install(db, enabled = true)
        val future = System.currentTimeMillis() * 1000 + 1000000000
        db.execSQL("UPDATE ${DriveReplicaJournal.STATE} SET clock=?", arrayOf(future))
        insertSource(db)
        assertEquals(future + 1, scalar(db, "SELECT clock FROM ${DriveReplicaJournal.STATE}"))
    }

    @Test fun auxiliaryTablesDoNotChangeRoomEntitySchemaOrReopenCompatibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "drive-replica-journal-${System.nanoTime()}"
        try {
            Room.databaseBuilder(context, MangaDatabase::class.java, name).build().useDatabase {
                DriveReplicaJournal.install(it.openHelper.writableDatabase, enabled = true)
            }
            Room.databaseBuilder(context, MangaDatabase::class.java, name).build().useDatabase {
                assertEquals(1L, scalar(it.openHelper.writableDatabase, "SELECT COUNT(*) FROM ${DriveReplicaJournal.STATE}"))
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun withDatabase(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build().useDatabase { block(it.openHelper.writableDatabase) }
    }

    private inline fun MangaDatabase.useDatabase(block: (MangaDatabase) -> Unit) {
        try { block(this) } finally { close() }
    }

    private fun insertSource(db: androidx.sqlite.db.SupportSQLiteDatabase) = db.execSQL(
        "INSERT INTO sources(source,enabled,sort_key,added_in,used_at,pinned,cf_state,nsfw_override) VALUES('test',0,0,0,0,0,0,NULL)",
    )
    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase) = scalar(db, "SELECT COUNT(*) FROM ${DriveReplicaJournal.CHANGES}")
    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String): Long = db.query(query).use {
        it.moveToFirst(); it.getLong(0)
    }
}
