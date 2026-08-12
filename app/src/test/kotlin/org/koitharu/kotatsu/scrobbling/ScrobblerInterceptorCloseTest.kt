package org.koitharu.kotatsu.scrobbling

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.closeQuietly
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListInterceptor
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.mal.data.MALInterceptor
import org.koitharu.kotatsu.scrobbling.shikimori.data.ShikimoriInterceptor
import java.util.concurrent.TimeUnit

class ScrobblerInterceptorCloseTest {

	@Test
	fun `anilist interceptor closes response before throwing on 401`() {
		val body = TrackingResponseBody()
		val chain = UnauthorizedChain(body)
		assertTrue(!body.isClosed)

		val thrown = runCatching { AniListInterceptor { null }.intercept(chain) }
			.exceptionOrNull()
		assertTrue(thrown is ScrobblerAuthRequiredException)
		assertTrue(body.isClosed)
	}

	@Test
	fun `mal interceptor closes response before throwing on 401`() {
		val body = TrackingResponseBody()
		val chain = UnauthorizedChain(body)
		assertTrue(!body.isClosed)

		val thrown = runCatching { MALInterceptor { null }.intercept(chain) }
			.exceptionOrNull()
		assertTrue(thrown is ScrobblerAuthRequiredException)
		assertTrue(body.isClosed)
	}

	@Test
	fun `shikimori interceptor closes response before throwing on 401`() {
		val body = TrackingResponseBody()
		val chain = UnauthorizedChain(body)
		assertTrue(!body.isClosed)

		val thrown = runCatching { ShikimoriInterceptor { null }.intercept(chain) }
			.exceptionOrNull()
		assertTrue(thrown is ScrobblerAuthRequiredException)
		assertTrue(body.isClosed)
	}

	private class UnauthorizedChain(private val body: TrackingResponseBody) : Interceptor.Chain {

		override fun request(): Request = Request.Builder().url("https://example.com/api/v2/data").build()

		override fun proceed(request: Request): Response = Response.Builder()
			.request(request)
			.protocol(okhttp3.Protocol.HTTP_1_1)
			.code(401)
			.message("Unauthorized")
			.body(body)
			.build()

		override fun connection(): Connection? = null

		override fun call(): Call = throw UnsupportedOperationException()

		override fun connectTimeoutMillis(): Int = 10_000

		override fun readTimeoutMillis(): Int = 10_000

		override fun writeTimeoutMillis(): Int = 10_000

		override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

		override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

		override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
	}

	private class TrackingResponseBody : ResponseBody() {

		var isClosed = false

		override fun contentLength(): Long = 0

		override fun contentType(): okhttp3.MediaType? = null

		override fun source(): okio.BufferedSource {
			return okio.Buffer()
		}

		override fun close() {
			isClosed = true
			super.close()
		}
	}
}
