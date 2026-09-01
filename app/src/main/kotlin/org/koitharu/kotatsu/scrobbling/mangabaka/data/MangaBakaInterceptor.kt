package org.koitharu.kotatsu.scrobbling.mangabaka.data

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val JSON = "application/json"

class MangaBakaInterceptor(
	private val accessToken: () -> String?,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val sourceRequest = chain.request()
		val request = sourceRequest.newBuilder().header(CommonHeaders.ACCEPT, JSON)
		val isAuthRequest = sourceRequest.url.pathSegments.contains("oauth2")
		if (!isAuthRequest) {
			accessToken()?.let { request.header(CommonHeaders.AUTHORIZATION, "Bearer $it") }
		}
		val response = chain.proceed(request.build())
		if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
			response.close()
			throw ScrobblerAuthRequiredException(ScrobblerService.MANGABAKA)
		}
		return response
	}
}
