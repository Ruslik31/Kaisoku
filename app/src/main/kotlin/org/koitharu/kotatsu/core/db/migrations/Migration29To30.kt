package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration29To30 : Migration(29, 30) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE sources ADD COLUMN `nsfw_override` INTEGER DEFAULT NULL")
		// Mihon sources used to be unconditionally treated as SFW. Now that the extension's
		// manifest flag is honoured, pin the previous state for sources the user already
		// enabled so they don't silently disappear when "Disable NSFW" is on. The override
		// stays clearable from source settings.
		db.execSQL("UPDATE sources SET nsfw_override = 0 WHERE source LIKE 'mihon:%' AND enabled = 1")
	}
}
