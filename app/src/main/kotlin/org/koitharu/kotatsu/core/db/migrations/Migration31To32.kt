package org.koitharu.kotatsu.core.db.migrations

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class Migration31To32(private val context: Context) : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE `scrobblings_new` (
                `scrobbler` INTEGER NOT NULL, `id` INTEGER NOT NULL, `manga_id` INTEGER NOT NULL,
                `target_id` INTEGER NOT NULL, `status` TEXT, `chapter` INTEGER NOT NULL,
                `comment` TEXT, `rating` REAL NOT NULL, `account_id` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`scrobbler`, `id`, `manga_id`, `account_id`)
            )
        """.trimIndent())
        db.execSQL("INSERT INTO scrobblings_new SELECT scrobbler, id, manga_id, target_id, status, chapter, comment, rating, 0 FROM scrobblings")
        ScrobblerService.entries.forEach { service ->
            val prefs = context.getSharedPreferences(service.name, Context.MODE_PRIVATE)
            val owner = prefs.getString("user", null)?.lineSequence()?.firstOrNull()?.toLongOrNull()
            if (owner != null && !prefs.getString("access_token", null).isNullOrEmpty()) {
                // Old manual rematching could leave multiple targets without timestamps. Do not guess which one is current.
                db.execSQL("""
                    UPDATE scrobblings_new SET account_id = ? WHERE scrobbler = ? AND manga_id IN (
                        SELECT manga_id FROM scrobblings_new WHERE scrobbler = ?
                        GROUP BY manga_id HAVING COUNT(*) = 1
                    )
                """.trimIndent(), arrayOf(owner, service.id, service.id))
            }
        }
        db.execSQL("DROP TABLE scrobblings")
        db.execSQL("ALTER TABLE scrobblings_new RENAME TO scrobblings")
    }
}
