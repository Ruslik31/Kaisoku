package org.koitharu.kotatsu.core.db

import android.content.Context
import android.content.res.Resources
import org.koitharu.kotatsu.sync.drive.SyncBackendSettings
import org.koitharu.kotatsu.sync.drive.SyncBackend
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.sync.drive.DriveReplicaJournal

class DatabasePrePopulateCallback(private val resources: Resources, private val context: Context? = null) : RoomDatabase.Callback() {

    override fun onOpen(db: SupportSQLiteDatabase) {
        DriveReplicaJournal.install(db, context?.getSharedPreferences("google_drive_sync", Context.MODE_PRIVATE)
            ?.getString(SyncBackendSettings.KEY_BACKEND, null) == SyncBackend.GOOGLE_DRIVE.name)
    }

	override fun onCreate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"INSERT INTO favourite_categories (created_at, sort_key, title, `order`, track, show_in_lib, `deleted_at`) VALUES (?,?,?,?,?,?,?)",
			arrayOf(
				System.currentTimeMillis(),
				1,
				resources.getString(R.string.read_later),
				SortOrder.NEWEST.name,
				1,
				1,
				0L,
			)
		)
	}
}
