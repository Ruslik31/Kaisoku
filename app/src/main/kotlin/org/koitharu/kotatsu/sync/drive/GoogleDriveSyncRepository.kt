package org.koitharu.kotatsu.sync.drive

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.domain.BackupSection
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DriveSyncResult {
	data object Success : DriveSyncResult
	data object SignInRequired : DriveSyncResult
	data class Error(val message: String?, val retryable: Boolean) : DriveSyncResult
}

data class DriveSyncProgress(val stage: Stage, val transferred: Long = 0L, val total: Long = 0L) {
	enum class Stage { IDLE, AUTHORIZING, DOWNLOADING, MERGING, PREPARING, UPLOADING }
}

@Singleton
class GoogleDriveSyncRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: SyncBackendSettings,
	private val auth: GoogleDriveAuth,
	private val api: GoogleDriveApi,
	private val backupRepository: BackupRepository,
	private val sourceSettingsStore: DriveSourceSettingsStore,
) {

	private val mutex = Mutex()
	private val mutableProgress = MutableStateFlow(DriveSyncProgress(DriveSyncProgress.Stage.IDLE))
	val progress = mutableProgress.asStateFlow()

	suspend fun sync(): DriveSyncResult = mutex.withLock {
		if (settings.backend != SyncBackend.GOOGLE_DRIVE) return DriveSyncResult.Success
		try {
			mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.AUTHORIZING)
			val authorization = auth.authorize()
			if (authorization !is DriveAuthorization.Token) {
				settings.lastSyncError = context.getString(R.string.drive_authorization_required)
				return DriveSyncResult.SignInRequired
			}
			val result = runSyncWithTokenRetry(authorization.value)
			if (result is DriveSyncResult.Success) {
				settings.lastSyncTimestamp = System.currentTimeMillis()
				settings.lastSyncError = null
			}
			result
		} catch (e: CancellationException) {
			throw e
		} catch (e: DriveSchemaException) {
			settings.lastSyncError = e.message
			DriveSyncResult.Error(e.message, retryable = false)
		} catch (e: DriveApiException) {
			settings.lastSyncError = e.message
			DriveSyncResult.Error(e.message, retryable = DriveTransferPolicy.isRetryableHttp(e.code))
		} catch (e: ApiException) {
			val message = when {
				DriveAuthorizationErrorPolicy.isApiConsoleSetupError(e.statusCode, e.message) -> {
					val identity = auth.getClientIdentity()
					"Google Drive OAuth client is not configured for ${identity.asPlainText().replace('\n', ' ')}"
				}
				e.statusCode == CommonStatusCodes.API_NOT_CONNECTED ->
					"Google Identity Authorization API is unavailable; update or enable Google Play services"
				else -> e.message ?: "Google Drive authorization failed (${e.statusCode})"
			}
			settings.lastSyncError = message
			DriveSyncResult.Error(
				message,
				retryable = e.statusCode == CommonStatusCodes.NETWORK_ERROR ||
					e.statusCode == CommonStatusCodes.CONNECTION_SUSPENDED_DURING_CALL,
			)
		} catch (e: Throwable) {
			settings.lastSyncError = e.message
			DriveSyncResult.Error(e.message, retryable = true)
		} finally {
			mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.IDLE)
		}
	}

	private suspend fun runSyncWithTokenRetry(initialToken: String): DriveSyncResult {
		var token = initialToken
		repeat(2) { attempt ->
			try {
				return syncWithToken(token)
			} catch (e: DriveApiException) {
				if (e.code != 401 || attempt != 0) throw e
				auth.clearRejectedToken(token)
				token = (auth.authorize() as? DriveAuthorization.Token)?.value
					?: return DriveSyncResult.SignInRequired
			}
		}
		return DriveSyncResult.Error("Google Drive authorization failed", retryable = false)
	}

	private suspend fun syncWithToken(token: String, remoteRefreshAttempt: Int = 0): DriveSyncResult {
		api.getUser(token)?.let { user ->
			settings.accountEmail = user.emailAddress
			settings.accountName = user.displayName
		}
		val remoteFiles = api.listSyncFiles(token).sortedBy { it.modifiedTime }
		val remote = remoteFiles.lastOrNull()

		if (settings.uploadSessionUrl != null && resumeUploadIfValid(token, remote)) {
			return DriveSyncResult.Success
		}

		// Merge every duplicate before writing one canonical latest snapshot. Duplicates are
		// retained because remote deletion is an explicit user action.
		remoteFiles.forEach { restoreRemote(token, it) }

		mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.PREPARING)
		val localBackup = tempFile("drive-local", ".zip")
		val payload = tempFile("drive-payload", ".json")
		try {
			ZipOutputStream(localBackup.outputStream().buffered()).use { output ->
				backupRepository.createBackup(output, progress = null, sections = settings.backupSections)
			}
			DriveSnapshotCodec.write(
				snapshot = payload,
				backup = localBackup,
				deviceId = settings.deviceId,
				sourceSettings = sourceSettingsStore.dump(),
			)
		} finally {
			localBackup.delete()
		}
		if (remote != null) {
			val current = api.getFile(token, remote.id)
			if (current.version != remote.version) {
				payload.delete()
				if (remoteRefreshAttempt == 0) return syncWithToken(token, remoteRefreshAttempt + 1)
				throw DriveApiException(409, "Google Drive sync data changed during merge")
			}
		}
		startUpload(token, remote, payload)
		return DriveSyncResult.Success
	}

	private suspend fun restoreRemote(token: String, remote: GoogleDriveApi.DriveFile) {
		val snapshot = download(token, remote)
		val backup = tempFile("drive-remote", ".zip")
		try {
			val metadata = DriveSnapshotCodec.read(snapshot, backup)
			mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.MERGING)
			ZipInputStream(backup.inputStream().buffered()).use { input ->
				backupRepository.restoreBackup(
					input = input,
					sections = settings.backupSections,
					progress = null,
					isMerge = true,
					replaceSections = setOf(BackupSection.SETTINGS_READER_GRID),
				)
			}
			sourceSettingsStore.restore(metadata.sourceSettings)
		} finally {
			backup.delete()
			snapshot.delete()
			clearDownloadState()
		}
	}

	private suspend fun startUpload(token: String, remote: GoogleDriveApi.DriveFile?, payload: File) {
		settings.clearUploadState(deletePayload = true)
		settings.uploadPayloadPath = payload.absolutePath
		settings.uploadPayloadHash = DriveSnapshotCodec.sha256(payload)
		settings.uploadLength = payload.length()
		settings.uploadOffset = 0L
		settings.uploadFileId = remote?.id
		settings.uploadBaseVersion = remote?.version
		settings.uploadCreatedAt = System.currentTimeMillis()
		settings.uploadSessionUrl = api.beginResumableUpload(token, remote?.id, payload.length())
		uploadPendingPayload(token)
	}

	private suspend fun resumeUploadIfValid(token: String, remote: GoogleDriveApi.DriveFile?): Boolean {
		val payload = settings.uploadPayloadPath?.let(::File) ?: return false
		val age = System.currentTimeMillis() - settings.uploadCreatedAt
		val validPayload = payload.isFile && payload.length() == settings.uploadLength &&
			DriveSnapshotCodec.sha256(payload) == settings.uploadPayloadHash
		if (!validPayload || age !in 0 until SESSION_LIFETIME_MS) {
			settings.clearUploadState()
			return false
		}
		if (settings.uploadFileId != null &&
			(remote?.id != settings.uploadFileId || remote?.version != settings.uploadBaseVersion)
		) {
			settings.clearUploadState()
			return false
		}
		val session = settings.uploadSessionUrl ?: return false
		val state = try {
			api.queryUpload(session, payload.length())
		} catch (e: DriveApiException) {
			if (e.code != 404) throw e
			settings.uploadOffset = 0L
			settings.uploadCreatedAt = System.currentTimeMillis()
			settings.uploadSessionUrl = api.beginResumableUpload(token, remote?.id, payload.length())
			null
		}
		if (state?.complete == true) {
			settings.clearUploadState()
			return true
		}
		if (state != null) settings.uploadOffset = state.nextOffset
		uploadPendingPayload(token)
		return true
	}

	private suspend fun uploadPendingPayload(token: String) {
		val payload = File(checkNotNull(settings.uploadPayloadPath))
		val total = payload.length()
		RandomAccessFile(payload, "r").use { input ->
			var offset = settings.uploadOffset.coerceIn(0L, total)
			var session = checkNotNull(settings.uploadSessionUrl)
			var restartedSession = false
			while (offset < total) {
				mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.UPLOADING, offset, total)
				input.seek(offset)
				val bytes = ByteArray(minOf(UPLOAD_CHUNK_SIZE.toLong(), total - offset).toInt())
				input.readFully(bytes)
				val response = try {
					api.uploadChunk(session, bytes, offset, total)
				} catch (e: DriveApiException) {
					if (e.code != 404 || restartedSession) throw e
					session = api.beginResumableUpload(token, settings.uploadFileId, total)
					settings.uploadSessionUrl = session
					settings.uploadCreatedAt = System.currentTimeMillis()
					offset = 0L
					settings.uploadOffset = offset
					restartedSession = true
					continue
				}
				if (response.complete) {
					offset = total
				} else {
					check(response.nextOffset > offset) { "Google Drive did not acknowledge upload progress" }
					offset = response.nextOffset
				}
				settings.uploadOffset = offset
			}
		}
		settings.clearUploadState()
	}

	private suspend fun download(token: String, remote: GoogleDriveApi.DriveFile): File = withContext(Dispatchers.IO) {
		val existingPath = context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
			.getString(KEY_DOWNLOAD_PATH, null)
		var file = existingPath?.let(::File)
		val storedVersion = context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
			.getString(KEY_DOWNLOAD_VERSION, null)
		val storedFileId = context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
			.getString(KEY_DOWNLOAD_FILE_ID, null)
		if (file?.isFile != true || storedVersion != remote.version || storedFileId != remote.id) {
			file?.delete()
			file = tempFile("drive-download", ".json")
			context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE).edit()
				.putString(KEY_DOWNLOAD_PATH, file.absolutePath)
				.putString(KEY_DOWNLOAD_VERSION, remote.version)
				.putString(KEY_DOWNLOAD_FILE_ID, remote.id)
				.apply()
		}
		var offset = file.length()
		val response = try {
			api.openDownload(token, remote.id, offset)
		} catch (e: DriveApiException) {
			if (offset <= 0L || e.code != 416) throw e
			file.outputStream().use { }
			offset = 0L
			api.openDownload(token, remote.id, offset)
		}
		response.use {
			if (offset > 0 && it.code == 206) {
				val returnedStart = DriveTransferPolicy.contentRangeStart(it.header("Content-Range"))
				check(returnedStart == offset) { "Google Drive returned an invalid download range" }
			}
			if (offset > 0 && it.code != 206) {
				file.outputStream().use { }
				offset = 0L
			}
			FileOutputStream(file, offset > 0).use { output ->
				val body = it.body.byteStream()
				val buffer = ByteArray(64 * 1024)
				while (true) {
					val count = body.read(buffer)
					if (count < 0) break
					output.write(buffer, 0, count)
					offset += count
					mutableProgress.value = DriveSyncProgress(
						DriveSyncProgress.Stage.DOWNLOADING,
						offset,
						remote.size?.toLongOrNull() ?: 0L,
					)
				}
			}
		}
		remote.size?.toLongOrNull()?.let { check(file.length() == it) { "Google Drive download size mismatch" } }
		remote.md5Checksum?.let { check(file.md5() == it) { "Google Drive download checksum mismatch" } }
		val current = api.getFile(token, remote.id)
		check(current.version == remote.version) { "Google Drive sync data changed during download" }
		file
	}

	private fun clearDownloadState() {
		context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
	}

	private fun tempFile(prefix: String, suffix: String): File =
		File.createTempFile(prefix, suffix, File(context.noBackupFilesDir, "drive-sync").apply { mkdirs() })

	private fun File.md5(): String {
		val digest = MessageDigest.getInstance("MD5")
		inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	companion object {
		private const val UPLOAD_CHUNK_SIZE = 1024 * 1024
		private const val SESSION_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
		private const val DOWNLOAD_PREFS = "google_drive_download"
		private const val KEY_DOWNLOAD_PATH = "path"
		private const val KEY_DOWNLOAD_VERSION = "version"
		private const val KEY_DOWNLOAD_FILE_ID = "file_id"
	}
}
