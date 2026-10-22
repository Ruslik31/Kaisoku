package org.koitharu.kotatsu.favourites

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.migrations.Migration32To33

class FavoriteOrderMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MangaDatabase::class.java)

    @Test fun additiveMigrationRetainsExistingMembershipDatesPinsAndReaderCursor() {
        val name = "favorite-order-${System.nanoTime()}"
        migrations.createDatabase(name, 32).use { db ->
            db.execSQL("INSERT INTO manga(manga_id,title,url,public_url,rating,nsfw,cover_url,source) VALUES(1,'Title','/1','https://example.org/1',0,0,'','MANGADEX')")
            db.execSQL("INSERT INTO favourite_categories VALUES(1,100,7,'Category','NEWEST',1,1,0)")
            db.execSQL("INSERT INTO favourites VALUES(1,1,9,1,123,0)")
            db.execSQL("INSERT INTO history VALUES(1,100,200,999,3,4500,0.4,0,100)")
        }
        migrations.runMigrationsAndValidate(name, 33, true, Migration32To33()).use { db ->
            db.query("SELECT sort_key,pinned,created_at,deleted_at FROM favourites").use {
                assertTrue(it.moveToFirst()); assertEquals(listOf(9L, 1L, 123L, 0L), (0..3).map(it::getLong))
            }
            db.query("SELECT chapter_id,page,scroll,updated_at FROM history").use {
                assertTrue(it.moveToFirst()); assertEquals(999L, it.getLong(0)); assertEquals(3, it.getInt(1))
                assertEquals(4500f, it.getFloat(2)); assertEquals(200L, it.getLong(3))
            }
            db.query("SELECT COUNT(*) FROM favorite_order").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
