package org.koitharu.kotatsu.sync.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveApi @Inject constructor(@BaseHttpClient baseClient: OkHttpClient) {

	private val client = baseClient.newBuilder().apply {
		interceptors().clear()
		networkInterceptors().clear()
		cache(null)
		connectTimeout(20, TimeUnit.SECONDS)
		readTimeout(60, TimeUnit.SECONDS)
		writeTimeout(60, TimeUnit.SECONDS)
		callTimeout(0, TimeUnit.SECONDS)
	}.build()
	private val json = Json { ignoreUnknownKeys = true }

	@Serializable
	data class DriveFile(
		@SerialName("id") val id: String,
		@SerialName("name") val name: String? = null,
		@SerialName("modifiedTime") val modifiedTime: String? = null,
		@SerialName("version") val version: String? = null,
		@SerialName("size") val size: String? = null,
		@SerialName("md5Checksum") val md5Checksum: String? = null,
	)

	@Serializable
	data class DriveUser(
		@SerialName("displayName") val displayName: String? = null,
		@SerialName("emailAddress") val emailAddress: String? = null,
	)

	@Serializable
	private data class FileList(@SerialName("files") val files: List<DriveFile> = emptyList())

	@Serializable
	private data class AboutResponse(@SerialName("user") val user: DriveUser? = null)

	suspend fun listSyncFiles(token: String): List<DriveFile> = executeJson(
		Request.Builder()
			.url(
				"$DRIVE_BASE/files".toHttpUrl().newBuilder()
					.addQueryParameter("spaces", "appDataFolder")
					.addQueryParameter("q", "name = '$SYNC_FILE_NAME' and trashed = false")
					.addQueryParameter("fields", "files(id,name,modifiedTime,version,size,md5Checksum)")
					.build(),
		)
			.bearer(token)
			.build(),
		FileList.serializer(),
	).files

	suspend fun getFile(token: String, fileId: String): DriveFile = executeJson(
		Request.Builder()
			.url(
				"$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
					.addQueryParameter("fields", "id,name,modifiedTime,version,size,md5Checksum")
					.build(),
		)
			.bearer(token)
			.build(),
		DriveFile.serializer(),
	)

	suspend fun getUser(token: String): DriveUser? = executeJson(
		Request.Builder()
			.url("$DRIVE_BASE/about?fields=user")
			.bearer(token)
			.build(),
		AboutResponse.serializer(),
	).user

	suspend fun openDownload(token: String, fileId: String, offset: Long): Response = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("$DRIVE_BASE/files/$fileId?alt=media")
			.bearer(token)
			.apply { if (offset > 0L) header("Range", "bytes=$offset-") }
			.build()
		client.newCall(request).execute().also { response ->
			if (response.code !in setOf(200, 206)) {
				val message = response.body.string().take(500)
				response.close()
				throw DriveApiException(response.code, message)
			}
		}
	}

	suspend fun beginResumableUpload(
		token: String,
		fileId: String?,
		length: Long,
	): String = withContext(Dispatchers.IO) {
		val metadata = if (fileId == null) {
			"{\"name\":\"$SYNC_FILE_NAME\",\"parents\":[\"appDataFolder\"]}"
		} else {
			"{}"
		}
		val url = if (fileId == null) {
			"$UPLOAD_BASE/files?uploadType=resumable"
		} else {
			"$UPLOAD_BASE/files/$fileId?uploadType=resumable"
		}
		val requestBody = metadata.toRequestBody(JSON_MEDIA_TYPE)
		val builder = Request.Builder()
			.url(url)
			.bearer(token)
			.header("X-Upload-Content-Type", "application/json")
			.header("X-Upload-Content-Length", length.toString())
			.method(if (fileId == null) "POST" else "PATCH", requestBody)
		client.newCall(builder.build()).execute().use { response ->
			if (!response.isSuccessful) throw response.toException()
			response.header("Location") ?: throw DriveApiException(response.code, "Missing resumable session URL")
		}
	}

	suspend fun queryUpload(sessionUrl: String, length: Long): DriveUploadResponse = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(sessionUrl)
			.header("Content-Range", "bytes */$length")
			.put(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
			.build()
		client.newCall(request).execute().use(::parseUploadResponse)
	}

	suspend fun uploadChunk(
		sessionUrl: String,
		bytes: ByteArray,
		offset: Long,
		length: Long,
	): DriveUploadResponse = withContext(Dispatchers.IO) {
		val end = offset + bytes.size - 1
		val request = Request.Builder()
			.url(sessionUrl)
			.header("Content-Range", "bytes $offset-$end/$length")
			.put(bytes.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		client.newCall(request).execute().use(::parseUploadResponse)
	}

	suspend fun deleteFile(token: String, fileId: String) = withContext(Dispatchers.IO) {
		client.newCall(Request.Builder().url("$DRIVE_BASE/files/$fileId").bearer(token).delete().build()).execute().use {
			if (!it.isSuccessful && it.code != 404) throw it.toException()
		}
	}

	private fun parseUploadResponse(response: Response): DriveUploadResponse {
		if (response.code == 308) {
			val acknowledged = DriveTransferPolicy.acknowledgedOffset(response.header("Range"))
			return DriveUploadResponse(acknowledged, complete = false, file = null)
		}
		if (!response.isSuccessful) throw response.toException()
		val body = response.body.string()
		val file = body.takeIf(String::isNotBlank)?.let { json.decodeFromString(DriveFile.serializer(), it) }
		return DriveUploadResponse(file?.size?.toLongOrNull() ?: Long.MAX_VALUE, complete = true, file = file)
	}

	private suspend fun <T> executeJson(
		request: Request,
		serializer: kotlinx.serialization.KSerializer<T>,
	): T = withContext(Dispatchers.IO) {
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw response.toException()
			json.decodeFromString(serializer, response.body.string())
		}
	}

	private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

	private fun Response.toException() = DriveApiException(code, body.string().take(500).ifBlank { message })

	data class DriveUploadResponse(val nextOffset: Long, val complete: Boolean, val file: DriveFile?)

	companion object {
		const val SYNC_FILE_NAME = "kaisoku_sync.json"
		private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
		private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
		private val JSON_MEDIA_TYPE = "application/json".toMediaType()
	}
}
