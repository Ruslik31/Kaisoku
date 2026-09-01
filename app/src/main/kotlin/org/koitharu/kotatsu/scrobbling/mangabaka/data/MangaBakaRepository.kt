package org.koitharu.kotatsu.scrobbling.mangabaka.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val REDIRECT_URI = "kotatsu://mangabaka-auth"
private const val BASE_WEB_URL = "https://mangabaka.org"
private const val BASE_API_URL = "https://api.mangabaka.org/v1"
private const val OAUTH_URL = "$BASE_WEB_URL/auth/oauth2"
private const val SCOPES = "library.read library.write offline_access openid"
private const val MANGA_PAGE_SIZE = 20
private const val RATING_MAX = 100

@Singleton
class MangaBakaRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.mangabaka_clientId)
	private var codeVerifier: String? = null

	override val oauthUrl: String
		get() {
			val verifier = generateCodeVerifier().also { codeVerifier = it }
			return "$OAUTH_URL/authorize".toHttpUrl().newBuilder()
				.addQueryParameter("client_id", clientId)
				.addQueryParameter("response_type", "code")
				.addQueryParameter("redirect_uri", REDIRECT_URI)
				.addQueryParameter("scope", SCOPES)
				.addQueryParameter("code_challenge", verifier.toS256Challenge())
				.addQueryParameter("code_challenge_method", "S256")
				.build()
				.toString()
		}

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
			.add("client_id", clientId)
			.add("redirect_uri", REDIRECT_URI)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("code", code)
			body.add("code_verifier", checkNotNull(codeVerifier) { "Authorization session expired" })
			body.add("code_challenge_method", "S256")
			body.add("scope", SCOPES)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken) { "Not authorized" })
		}
		val response = okHttp.newCall(
			Request.Builder().post(body.build()).url("$OAUTH_URL/token").build(),
		).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getStringOrNull("refresh_token") ?: storage.refreshToken
		if (code != null) {
			codeVerifier = null
		}
	}

	override suspend fun loadUser(): ScrobblerUser {
		val response = okHttp.newCall(Request.Builder().get().url("$BASE_API_URL/my/profile").build())
			.await().parseJson().getJSONObject("data")
		val id = response.getString("id")
		return ScrobblerUser(
			id = id.hashCode().toLong(),
			nickname = response.getStringOrNull("nickname")
				?: response.getStringOrNull("preferred_username")
				?: id,
			avatar = response.getStringOrNull("avatar") ?: response.getStringOrNull("picture"),
			service = ScrobblerService.MANGABAKA,
		).also { storage.user = it }
	}

	override fun logout() = storage.clear()

	override suspend fun unregister(mangaId: Long) {
		db.getScrobblingDao().delete(ScrobblerService.MANGABAKA.id, mangaId)
	}

	override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
		val url = "$BASE_API_URL/series/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("limit", MANGA_PAGE_SIZE.toString())
			.addQueryParameter("page", (offset / MANGA_PAGE_SIZE + 1).toString())
			.build()
		val response = okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
		check(response.has("data")) { "Invalid response: $response" }
		return response.getJSONArray("data").mapJSONNotNull { jsonToManga(it, query) }
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val json = okHttp.newCall(Request.Builder().url("$BASE_API_URL/series/$id").get().build())
			.await().parseJson().getJSONObject("data")
		return ScrobblerMangaInfo(
			id = json.getLong("id"),
			name = json.title(),
			cover = json.coverUrl().orEmpty(),
			url = "$BASE_WEB_URL/${json.getLong("id")}",
			descriptionHtml = json.getStringOrNull("description").orEmpty(),
		)
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		libraryEntry(scrobblerMangaId)?.let {
			saveLibraryEntry(mangaId, scrobblerMangaId, it, comment = null)
			return true
		}
		val body = JSONObject().put("state", "reading").toString().toJsonBody()
		okHttp.newCall(Request.Builder().url("$BASE_API_URL/my/library/$scrobblerMangaId").post(body).build())
			.await().parseJson()
		fetchRate(mangaId, scrobblerMangaId, null)
		return false
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val current = requireEntity(mangaId)
		pushRate(mangaId, rateId.toLong(), current.status, chapter, current.rating, current.comment, false)
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	) {
		val current = requireEntity(mangaId)
		pushRate(
			mangaId,
			rateId.toLong(),
			status ?: current.status,
			current.chapter,
			rating / RATING_MAX,
			comment,
			setStartDate,
		)
	}

	private suspend fun requireEntity(mangaId: Long) = requireNotNull(
		db.getScrobblingDao().find(ScrobblerService.MANGABAKA.id, mangaId),
	) { "Scrobbling info for manga $mangaId not found" }

	private suspend fun pushRate(
		mangaId: Long,
		targetId: Long,
		status: String?,
		chapter: Int,
		rating: Float,
		comment: String?,
		setStartDate: Boolean,
	): ScrobblingEntity {
		val json = JSONObject()
			.put("state", status ?: "reading")
			.put("progress_chapter", if (chapter > 0) chapter else JSONObject.NULL)
			.put(
				"rating",
				(rating * RATING_MAX).toInt().coerceIn(0, RATING_MAX).takeIf { it > 0 } ?: JSONObject.NULL,
			)
		if (setStartDate) {
			json.put("start_date", LocalDate.now().toString())
		}
		val body = json.toString().toJsonBody()
		okHttp.newCall(Request.Builder().url("$BASE_API_URL/my/library/$targetId").put(body).build())
			.await().parseJson()
		return saveRate(mangaId, targetId, status, chapter, rating, comment)
	}

	private suspend fun fetchRate(mangaId: Long, targetId: Long, comment: String?): ScrobblingEntity {
		val json = checkNotNull(libraryEntry(targetId)) { "Series $targetId is not in the library" }
		return saveLibraryEntry(mangaId, targetId, json, comment)
	}

	private suspend fun libraryEntry(targetId: Long): JSONObject? {
		val response = okHttp.newCall(Request.Builder().url("$BASE_API_URL/my/library/$targetId").get().build()).await()
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		return response.parseJson().getJSONObject("data")
	}

	private suspend fun saveLibraryEntry(
		mangaId: Long,
		targetId: Long,
		json: JSONObject,
		comment: String?,
	): ScrobblingEntity = saveRate(
			mangaId = mangaId,
			targetId = targetId,
			status = json.getStringOrNull("state"),
			chapter = json.optDouble("progress_chapter", 0.0).takeUnless { it.isNaN() }?.toInt() ?: 0,
			rating = (json.optInt("rating", 0).toFloat() / RATING_MAX).coerceIn(0f, 1f),
			comment = comment,
		)

	private suspend fun saveRate(
		mangaId: Long,
		targetId: Long,
		status: String?,
		chapter: Int,
		rating: Float,
		comment: String?,
	): ScrobblingEntity {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.MANGABAKA.id,
			id = targetId.toInt(),
			mangaId = mangaId,
			targetId = targetId,
			status = status,
			chapter = chapter,
			comment = comment,
			rating = rating.coerceIn(0f, 1f),
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	private fun jsonToManga(json: JSONObject, sourceTitle: String): ScrobblerManga? {
		if (json.getStringOrNull("type")?.contains("novel", ignoreCase = true) == true) {
			return null
		}
		val title = json.title()
		return ScrobblerManga(
			id = json.getLong("id"),
			name = title,
			altName = json.getStringOrNull("native_title"),
			cover = json.coverUrl(),
			url = "$BASE_WEB_URL/${json.getLong("id")}",
			isBestMatch = title.equals(sourceTitle, ignoreCase = true),
		)
	}

	private fun JSONObject.title(): String = getStringOrNull("title")
		?: getStringOrNull("romanized_title")
		?: getStringOrNull("native_title")
		?: "#${getLong("id")}"

	private fun JSONObject.coverUrl(): String? = optJSONObject("cover")
		?.optJSONObject("x250")
		?.getStringOrNull("x1")

	private fun String.toJsonBody() = toRequestBody("application/json".toMediaType())

	private fun generateCodeVerifier(): String {
		val bytes = ByteArray(50)
		SecureRandom().nextBytes(bytes)
		return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
	}

	private fun String.toS256Challenge(): String = Base64.encodeToString(
		MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.US_ASCII)),
		Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
	)
}
