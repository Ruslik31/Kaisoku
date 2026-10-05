package org.koitharu.kotatsu.sync.drive

import androidx.sqlite.db.SupportSQLiteDatabase

/** Extra tables do not change Room's entity schema or prevent older Kaisoku clients opening the DB. */
object DriveReplicaJournal {
    const val STATE = "kaisoku_drive_state"
    const val CHANGES = "kaisoku_drive_changes"
    const val RECORDS = "kaisoku_drive_records"
    const val CATEGORIES = "kaisoku_drive_categories"
    const val ALIASES = "kaisoku_drive_category_aliases"
    const val LEGACY_CATEGORIES = "kaisoku_drive_legacy_categories"

    data class Table(val section: String, val table: String, val columns: List<String>, val fields: List<String>)

    val tables = listOf(
        Table("categories", "favourite_categories", listOf("category_id"),
            listOf("title", "sort_key", "order", "track", "show_in_lib", "deleted_at")),
        Table("favourites", "favourites", listOf("manga_id", "category_id"),
            listOf("sort_key", "pinned", "created_at", "deleted_at")),
        Table("history", "history", listOf("manga_id"),
            listOf("chapter_id", "page", "scroll", "percent", "updated_at", "deleted_at")),
        Table("bookmarks", "bookmarks", listOf("manga_id", "page_id"), emptyList()),
        Table("sources", "sources", listOf("source"),
            listOf("enabled", "sort_key", "pinned", "nsfw_override")),
        Table("scrobbling", "scrobblings", listOf("scrobbler", "id", "manga_id"), emptyList()),
        Table("statistics", "stats", listOf("manga_id", "started_at"), emptyList()),
        Table("manga_preferences", "preferences", listOf("manga_id"), emptyList()),
        Table("tracks", "tracks", listOf("manga_id"), emptyList()),
    )

    fun install(db: SupportSQLiteDatabase, enabled: Boolean = false) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $STATE (id INTEGER PRIMARY KEY, clock INTEGER NOT NULL, muted INTEGER NOT NULL, enabled INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO $STATE VALUES(1, 0, 0, 0)")
        db.execSQL("UPDATE $STATE SET muted=0,enabled=${if (enabled) 1 else 0} WHERE id=1")
        db.execSQL("CREATE TABLE IF NOT EXISTS $CHANGES (section TEXT NOT NULL, record_key TEXT NOT NULL, version INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(section,record_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $RECORDS (account TEXT NOT NULL, section TEXT NOT NULL, record_key TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(account,section,record_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $CATEGORIES (category_id INTEGER PRIMARY KEY, uid TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $ALIASES (old_uid TEXT PRIMARY KEY, uid TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $LEGACY_CATEGORIES (account TEXT NOT NULL, file_id TEXT NOT NULL, category_id INTEGER NOT NULL, uid TEXT NOT NULL, PRIMARY KEY(account,file_id,category_id))")
        db.execSQL("INSERT OR IGNORE INTO $CATEGORIES SELECT category_id,'legacy-'||hex(title) FROM favourite_categories")
        for (table in tables) {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val row = if (operation == "DELETE") "OLD" else "NEW"
                val key = table.columns.joinToString(" || ':' || ") { "$row.`$it`" }
                val fields = if (operation == "UPDATE" && table.fields.isNotEmpty()) {
                    " OF " + table.fields.joinToString(",") { "`$it`" }
                } else ""
                val changed = if (operation == "UPDATE" && table.fields.isNotEmpty()) {
                    " AND (" + table.fields.joinToString(" OR ") { "NEW.`$it` IS NOT OLD.`$it`" } + ")"
                } else ""
                val deleted = when {
                    operation == "DELETE" -> "1"
                    table.section == "sources" && operation == "UPDATE" -> "CASE WHEN NEW.enabled=0 THEN 1 ELSE 0 END"
                    else -> "0"
                }
                val categoryMapping = if (table.section == "categories" && operation == "INSERT") {
                    "INSERT OR IGNORE INTO $CATEGORIES VALUES(NEW.category_id,'category-'||lower(hex(randomblob(16))));"
                } else ""
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS kaisoku_drive_${table.table}_${operation.lowercase()}
                    AFTER $operation$fields ON `${table.table}`
                    WHEN (SELECT muted FROM $STATE WHERE id=1)=0 AND (SELECT enabled FROM $STATE WHERE id=1)=1$changed
                    BEGIN
                        $categoryMapping
                        UPDATE $STATE SET clock=MAX(clock+1,CAST((julianday('now')-2440587.5)*86400000000 AS INTEGER)) WHERE id=1;
                        INSERT OR REPLACE INTO $CHANGES VALUES('${table.section}',CAST($key AS TEXT),(SELECT clock FROM $STATE WHERE id=1),$deleted);
                    END
                """.trimIndent())
            }
        }
    }
}
