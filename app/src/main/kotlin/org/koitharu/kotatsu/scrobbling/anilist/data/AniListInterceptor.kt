package org.koitharu.kotatsu.scrobbling.anilist.data

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val JSON = "application/json"

class AniListInterceptor(
	private val accessTokenProvider: () -> String?,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val sourceRequest = chain.request()
		val request = sourceRequest.newBuilder()
		request.header(CommonHeaders.CONTENT_TYPE, JSON)
		request.header(CommonHeaders.ACCEPT, JSON)
		val isAuthRequest = sourceRequest.url.pathSegments.contains("oauth")
		if (!isAuthRequest) {
			accessTokenProvider()?.let {
				request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
			}
		}
		val response = chain.proceed(request.build())
		if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
			response.closeQuietly()
			throw ScrobblerAuthRequiredException(ScrobblerService.ANILIST)
		}
		return response
	}
}
