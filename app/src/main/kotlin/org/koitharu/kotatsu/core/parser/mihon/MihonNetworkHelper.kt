package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class MihonNetworkHelper(
	private val httpClient: OkHttpClient,
	private val defaultUserAgent: () -> String,
) : NetworkHelper() {

	override val client: OkHttpClient = httpClient.newBuilder()
		.addInterceptor(MihonCloudFlareInterceptor())
		.build()

	override fun defaultUserAgentProvider(): String = defaultUserAgent()

	/**
	 * Routes Cloudflare challenges for extension-issued requests whose `MangaSource` tag was never
	 * set (e.g. direct use of `network.client.newCall(...)` inside an extension's helper, or a
	 * follow-redirect fetch). Blocks are passed through so Kagane-style "seed-the-cookie" calls
	 * can observe the 403 themselves; captcha / challenge pages (PROTECTION_CAPTCHA) throw
	 * `CloudFlareProtectedException` tagged with the source whose host matches the request.
	 */
	private class MihonCloudFlareInterceptor : Interceptor {

		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val response = chain.proceed(request)
			val source = request.tag(MangaSource::class.java)
				?: MihonSourceRegistry.findSourceByHost(request.url.host)
			when (CloudFlareHelper.checkResponseForProtection(response)) {
				CloudFlareHelper.PROTECTION_BLOCKED -> {
					// Mihon passes the block page back to the caller so a source that expects to
					// be blocked (e.g. Kagane's `getIntegrityToken` cookie seed) can still observe
					// the response end-to-end.
					return response
				}

				CloudFlareHelper.PROTECTION_CAPTCHA -> {
					val url = request.toChallengeUrl()
					response.close()
					throw CloudFlareProtectedException(
						url = url,
						source = source,
						headers = request.headers,
					)
				}

				else -> return response
			}
		}

		private fun Request.toChallengeUrl(): String {
			val referer = header("Referer")?.toHttpUrlOrNull()
			if (referer != null && referer.host == url.host) {
				return referer.newBuilder().query(null).fragment(null).build().toString()
			}
			return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
		}
	}
}
