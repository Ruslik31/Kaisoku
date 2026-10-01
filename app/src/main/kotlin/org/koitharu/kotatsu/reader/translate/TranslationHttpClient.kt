package org.koitharu.kotatsu.reader.translate

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.parsers.util.await
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Keep provider responses intact so retries and copied errors retain status, headers and body. */
internal class TranslationHttpClient(baseClient: OkHttpClient) {
	private val client = baseClient.newBuilder().apply {
		// The manga interceptor throws away 429 bodies and prevents provider retry handling.
		interceptors().removeAll { it is RateLimitInterceptor }
		// Deliberative models need time to finish; stalled requests remain bounded and cancellable.
		callTimeout(180, TimeUnit.SECONDS)
		readTimeout(180, TimeUnit.SECONDS)
	}.build()

	suspend fun execute(request: Request, beforeRequest: suspend () -> Unit): Response {
		var attempt = 0
		while (true) {
			beforeRequest()
			val response = try {
				client.newCall(request).await()
			} catch (e: IOException) {
				throw TranslateException.Network(e)
			}
			if (response.isSuccessful || attempt >= MAX_RETRIES || !TranslationRetry.shouldRetry(response.code)) {
				return response
			}
			val retryAfter = response.header("Retry-After")
			response.close()
			delay(TranslationRetry.delayMillis(attempt, retryAfter))
			attempt++
		}
	}

	private companion object {
		const val MAX_RETRIES = 3
	}
}
