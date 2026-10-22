package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration32To33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorite_order (manga_id INTEGER NOT NULL, scope_id INTEGER NOT NULL, " +
            "rank INTEGER NOT NULL, updated_at INTEGER NOT NULL, revision TEXT NOT NULL, " +
            "PRIMARY KEY(manga_id,scope_id), FOREIGN KEY(manga_id) REFERENCES manga(manga_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_order_manga_id ON favorite_order(manga_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_order_scope_id ON favorite_order(scope_id)")
    }
}
