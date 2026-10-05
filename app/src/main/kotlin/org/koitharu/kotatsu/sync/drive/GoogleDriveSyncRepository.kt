package org.koitharu.kotatsu.sync.drive

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
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
    private val replicaStore: DriveReplicaStore,
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
        val user = api.getUser(token) ?: throw IllegalStateException("Google Drive account identity is unavailable")
        val account = user.emailAddress?.lowercase()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Google Drive account identity is unavailable")
        settings.accountEmail = user.emailAddress
        settings.accountName = user.displayName
        var remoteFiles = api.listSyncFiles(token).sortedBy { it.modifiedTime }
        if (settings.uploadSessionUrl != null) {
            if (settings.uploadAccount != account) settings.clearUploadState()
            else resumeUploadIfValid(token, remoteFiles.find { it.id == settings.uploadFileId })
            remoteFiles = api.listSyncFiles(token).sortedBy { it.modifiedTime }
            // A resumed payload can predate local edits: continue and capture the current state.
        }
        val replicas = mutableListOf<DriveReplica>()
        val legacy = mutableListOf<DriveReplicaStore.Legacy>()
        val temporary = mutableListOf<File>()
        val ownFiles = mutableListOf<GoogleDriveApi.DriveFile>()
        var compatibility: GoogleDriveApi.DriveFile? = null
        val sectionScope = settings.backupSections.map { it.name }.sorted().joinToString(",")
        val migrationScope = "$account/$sectionScope"
        try {
            for (remote in remoteFiles.filter { it.name != GoogleDriveApi.SYNC_FILE_NAME }) {
                val snapshot = download(token, remote).also(temporary::add)
                val replica = DriveReplicaCodec.read(snapshot)
                check(remote.name == GoogleDriveApi.replicaName(replica.device)) { "Google Drive replica identity mismatch" }
                replicas += replica
                if (replica.device == settings.deviceId) ownFiles += remote
                clearDownloadState()
            }
            for (remote in remoteFiles.filter { it.name == GoogleDriveApi.SYNC_FILE_NAME }) {
                val snapshot = download(token, remote).also(temporary::add)
                val backup = tempFile("drive-legacy", ".zip").also(temporary::add)
                val metadata = DriveSnapshotCodec.read(snapshot, backup)
                if (metadata.deviceId == settings.deviceId && metadata.replicaBridge) compatibility = remote
                val hasReplica = metadata.replicaBridge && replicas.any { it.device == metadata.deviceId }
                if (!hasReplica && !replicaStore.hasLegacyVersion(migrationScope, remote.id, remote.version.orEmpty())) {
                    legacy += DriveReplicaStore.Legacy(remote.id, remote.version.orEmpty(), backup, metadata)
                }
                clearDownloadState()
            }
            mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.MERGING)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (settings.backend != SyncBackend.GOOGLE_DRIVE) return DriveSyncResult.Success
            val replica = replicaStore.merge(account, settings.deviceId, settings.backupSections, replicas, legacy)
            // Check every file, including legacy duplicates, before publication. Another device only writes its own replica.
            val changed = remoteFiles.any { api.getFile(token, it.id).version != it.version }
            if (changed) {
                if (remoteRefreshAttempt < 1) return syncWithToken(token, remoteRefreshAttempt + 1)
                throw DriveApiException(409, "Google Drive sync data changed during merge")
            }
            mutableProgress.value = DriveSyncProgress(DriveSyncProgress.Stage.PREPARING)
            val payload = tempFile("drive-replica", ".json.gz")
            DriveReplicaCodec.write(payload, replica)
            startUpload(token, ownFiles.lastOrNull(), payload, account, GoogleDriveApi.replicaName(settings.deviceId))
            replicaStore.acknowledgeLegacy(migrationScope, replica)
            // A schema-1 bridge lets older clients read newer data. New clients ignore bridges when the matching
            // authoritative replica exists. If an old client edits one, its ordinary writer removes the marker.
            val backup = tempFile("drive-compatibility", ".zip").also(temporary::add)
            replicaStore.writeCompatibilityBackup(backup, replica)
            val bridge = tempFile("drive-bridge", ".json")
            DriveSnapshotCodec.write(bridge, backup, settings.deviceId,
                sourceSettings = replicaStore.replicaSourceSettings(replica), replicaBridge = true)
            startUpload(token, compatibility, bridge, account, GoogleDriveApi.SYNC_FILE_NAME)
            return DriveSyncResult.Success
        } finally {
            temporary.forEach(File::delete)
        }
    }

	private suspend fun startUpload(
        token: String, remote: GoogleDriveApi.DriveFile?, payload: File, account: String, fileName: String,
    ) {
		settings.clearUploadState(deletePayload = true)
		settings.uploadPayloadPath = payload.absolutePath
        settings.uploadAccount = account
        settings.uploadFileName = fileName
		settings.uploadPayloadHash = DriveSnapshotCodec.sha256(payload)
		settings.uploadLength = payload.length()
		settings.uploadOffset = 0L
		settings.uploadFileId = remote?.id
		settings.uploadBaseVersion = remote?.version
		settings.uploadCreatedAt = System.currentTimeMillis()
		settings.uploadSessionUrl = api.beginResumableUpload(token, remote?.id, payload.length(),
            settings.uploadFileName ?: GoogleDriveApi.SYNC_FILE_NAME)
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
			settings.uploadSessionUrl = api.beginResumableUpload(token, remote?.id, payload.length(),
            settings.uploadFileName ?: GoogleDriveApi.SYNC_FILE_NAME)
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
					session = api.beginResumableUpload(token, settings.uploadFileId, total,
                        settings.uploadFileName ?: GoogleDriveApi.SYNC_FILE_NAME)
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
