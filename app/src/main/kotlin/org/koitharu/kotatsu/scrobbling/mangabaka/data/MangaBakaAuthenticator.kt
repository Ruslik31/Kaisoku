package org.koitharu.kotatsu.scrobbling.mangabaka.data

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import javax.inject.Inject
import javax.inject.Provider

class MangaBakaAuthenticator @Inject constructor(
	@ScrobblerType(ScrobblerService.MANGABAKA) private val storage: ScrobblerStorage,
	private val repositoryProvider: Provider<MangaBakaRepository>,
) : Authenticator {

	override fun authenticate(route: Route?, response: Response): Request? {
		val accessToken = storage.accessToken ?: return null
		if (response.request.header(CommonHeaders.AUTHORIZATION)?.startsWith("Bearer") != true) {
			return null
		}
		synchronized(this) {
			val currentAccessToken = storage.accessToken ?: return null
			if (accessToken != currentAccessToken) {
				return response.request.withAccessToken(currentAccessToken)
			}
			val updatedAccessToken = runCatching {
				runBlocking { repositoryProvider.get().authorize(null) }
				storage.accessToken
			}.onFailure { it.printStackTraceDebug() }.getOrNull() ?: return null
			return response.request.withAccessToken(updatedAccessToken)
		}
	}

	private fun Request.withAccessToken(accessToken: String): Request = newBuilder()
		.header(CommonHeaders.AUTHORIZATION, "Bearer $accessToken")
		.build()
}
