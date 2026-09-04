package org.koitharu.kotatsu.sync.drive

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.domain.BackupSection
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncBackend {
	NONE,
	KAISOKU_SERVER,
	GOOGLE_DRIVE,
}

enum class DriveContentSection(val backupSections: Set<BackupSection>) {
	LIBRARY(setOf(BackupSection.CATEGORIES, BackupSection.FAVOURITES)),
	HISTORY(setOf(BackupSection.HISTORY)),
	BOOKMARKS(setOf(BackupSection.BOOKMARKS)),
	TRACKING(setOf(BackupSection.TRACKS, BackupSection.SCROBBLING)),
	SETTINGS(
		setOf(
			BackupSection.SETTINGS,
			BackupSection.SETTINGS_READER_GRID,
			BackupSection.SOURCES,
			BackupSection.SAVED_FILTERS,
			BackupSection.MANGA_PREFERENCES,
		),
	),
	STATISTICS(setOf(BackupSection.STATS)),
}

@Singleton
class SyncBackendSettings @Inject constructor(@ApplicationContext private val context: Context) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	var backend: SyncBackend
		get() = prefs.getString(KEY_BACKEND, null)?.let { runCatching { SyncBackend.valueOf(it) }.getOrNull() }
			?: migrateBackend()
		set(value) = prefs.edit { putString(KEY_BACKEND, value.name) }

	var accountEmail: String?
		get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_EMAIL, value) }

	var accountName: String?
		get() = prefs.getString(KEY_ACCOUNT_NAME, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

	val deviceId: String
		get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { value ->
			prefs.edit { putString(KEY_DEVICE_ID, value) }
		}

	var intervalMinutes: Int
		get() = prefs.getInt(KEY_INTERVAL, 360)
		set(value) = prefs.edit { putInt(KEY_INTERVAL, value) }

	var isWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
		set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

	var isSyncOnStart: Boolean
		get() = prefs.getBoolean(KEY_SYNC_ON_START, true)
		set(value) = prefs.edit { putBoolean(KEY_SYNC_ON_START, value) }

	var contentSections: Set<DriveContentSection>
		get() = prefs.getStringSet(KEY_CONTENT_SECTIONS, null)
			?.mapNotNullTo(hashSetOf()) { runCatching { DriveContentSection.valueOf(it) }.getOrNull() }
			?: DriveContentSection.entries.toSet()
		set(value) = prefs.edit { putStringSet(KEY_CONTENT_SECTIONS, value.mapTo(hashSetOf()) { it.name }) }

	val backupSections: Set<BackupSection>
		get() = contentSections.flatMapTo(hashSetOf(), DriveContentSection::backupSections) + BackupSection.INDEX

	var lastSyncTimestamp: Long
		get() = prefs.getLong(KEY_LAST_SYNC, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

	var lastSyncError: String?
		get() = prefs.getString(KEY_LAST_ERROR, null)
		set(value) = prefs.edit { putString(KEY_LAST_ERROR, value) }

	var uploadSessionUrl: String?
		get() = prefs.getString(KEY_UPLOAD_SESSION, null)
		set(value) = prefs.edit { putString(KEY_UPLOAD_SESSION, value) }

	var uploadFileId: String?
		get() = prefs.getString(KEY_UPLOAD_FILE_ID, null)
		set(value) = prefs.edit { putString(KEY_UPLOAD_FILE_ID, value) }

	var uploadBaseVersion: String?
		get() = prefs.getString(KEY_UPLOAD_BASE_VERSION, null)
		set(value) = prefs.edit { putString(KEY_UPLOAD_BASE_VERSION, value) }

	var uploadPayloadPath: String?
		get() = prefs.getString(KEY_UPLOAD_PATH, null)
		set(value) = prefs.edit { putString(KEY_UPLOAD_PATH, value) }

	var uploadPayloadHash: String?
		get() = prefs.getString(KEY_UPLOAD_HASH, null)
		set(value) = prefs.edit { putString(KEY_UPLOAD_HASH, value) }

	var uploadLength: Long
		get() = prefs.getLong(KEY_UPLOAD_LENGTH, 0L)
		set(value) = prefs.edit { putLong(KEY_UPLOAD_LENGTH, value) }

	var uploadOffset: Long
		get() = prefs.getLong(KEY_UPLOAD_OFFSET, 0L)
		set(value) = prefs.edit { putLong(KEY_UPLOAD_OFFSET, value) }

	var uploadCreatedAt: Long
		get() = prefs.getLong(KEY_UPLOAD_CREATED_AT, 0L)
		set(value) = prefs.edit { putLong(KEY_UPLOAD_CREATED_AT, value) }

	fun clearUploadState(deletePayload: Boolean = true) {
		if (deletePayload) uploadPayloadPath?.let(::File)?.takeIf(File::exists)?.delete()
		prefs.edit {
			remove(KEY_UPLOAD_SESSION)
			remove(KEY_UPLOAD_FILE_ID)
			remove(KEY_UPLOAD_BASE_VERSION)
			remove(KEY_UPLOAD_PATH)
			remove(KEY_UPLOAD_HASH)
			remove(KEY_UPLOAD_LENGTH)
			remove(KEY_UPLOAD_OFFSET)
			remove(KEY_UPLOAD_CREATED_AT)
		}
	}

	private fun migrateBackend(): SyncBackend {
		val accountType = context.getString(R.string.account_type_sync)
		val account = AccountManager.get(context).getAccountsByType(accountType).firstOrNull()
		val wasActive = account != null && ContentResolver.getMasterSyncAutomatically() && (
			ContentResolver.getSyncAutomatically(account, context.getString(R.string.sync_authority_favourites)) ||
				ContentResolver.getSyncAutomatically(account, context.getString(R.string.sync_authority_history))
			)
		val migrated = if (wasActive) {
			SyncBackend.KAISOKU_SERVER
		} else {
			SyncBackend.NONE
		}
		backend = migrated
		return migrated
	}

	companion object {
		const val KEY_BACKEND = "sync_backend"
		const val KEY_DRIVE_AUTHORIZE = "drive_authorize"
		const val KEY_DRIVE_SYNC_NOW = "drive_sync_now"
		const val KEY_DRIVE_DISCONNECT = "drive_disconnect"
		const val KEY_DRIVE_WIFI_ONLY = "drive_wifi_only"
		const val KEY_DRIVE_INTERVAL = "drive_interval"
		const val KEY_DRIVE_SYNC_ON_START = "drive_sync_on_start"
		const val KEY_DRIVE_CONTENT = "drive_content"

		private const val PREFS_NAME = "google_drive_sync"
		private const val KEY_ACCOUNT_EMAIL = "account_email"
		private const val KEY_ACCOUNT_NAME = "account_name"
		private const val KEY_DEVICE_ID = "device_id"
		private const val KEY_INTERVAL = "interval"
		private const val KEY_WIFI_ONLY = "wifi_only"
		private const val KEY_SYNC_ON_START = "sync_on_start"
		private const val KEY_LAST_SYNC = "last_sync"
		private const val KEY_LAST_ERROR = "last_error"
		private const val KEY_UPLOAD_SESSION = "upload_session"
		private const val KEY_UPLOAD_FILE_ID = "upload_file_id"
		private const val KEY_UPLOAD_BASE_VERSION = "upload_base_version"
		private const val KEY_UPLOAD_PATH = "upload_path"
		private const val KEY_UPLOAD_HASH = "upload_hash"
		private const val KEY_UPLOAD_LENGTH = "upload_length"
		private const val KEY_UPLOAD_OFFSET = "upload_offset"
		private const val KEY_UPLOAD_CREATED_AT = "upload_created_at"
		private const val KEY_CONTENT_SECTIONS = "content_sections"
	}
}
