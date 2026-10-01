package org.koitharu.kotatsu.reader.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TranslationHttpClientTest {
	private val request = Request.Builder().url("https://translation.invalid/v1/translate").build()

	@Test fun inherited429InterceptorNoLongerPreventsRetryAndMangaClientIsUnchanged() = runTest {
		val calls = AtomicInteger()
		val base = fixture { chain ->
			if (calls.incrementAndGet() == 2) response(chain, 200, "translated") else response(chain, 429, "quota")
		}
		var starts = 0
		TranslationHttpClient(base).execute(request) { starts++ }.use {
			assertEquals(200, it.code)
			assertEquals("translated", it.body?.string())
		}
		assertEquals(2, calls.get())
		assertEquals(2, starts)
		assertThrows(TooManyRequestExceptions::class.java) { base.newCall(request).execute() }
	}

	@Test fun exhausted429RetriesReturnFullProviderResponseInsteadOfNetworkError() = runTest {
		val calls = AtomicInteger()
		val body = """{"error":{"message":"Daily quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
		val base = fixture { chain ->
			calls.incrementAndGet()
			response(chain, 429, body).newBuilder().header("x-request-id", "quota-request").build()
		}
		TranslationHttpClient(base).execute(request) {}.use {
			assertEquals(429, it.code)
			assertEquals("0", it.header("Retry-After"))
			assertEquals("quota-request", it.header("x-request-id"))
			assertEquals(body, it.body?.string())
		}
		assertEquals(4, calls.get())
	}

	@Test fun permanentProviderFailureDoesNotRetry() = runTest {
		val calls = AtomicInteger()
		TranslationHttpClient(fixture { chain ->
			calls.incrementAndGet()
			response(chain, 403, "forbidden")
		}).execute(request) {}.use {
			assertEquals(403, it.code)
			assertEquals("forbidden", it.body?.string())
		}
		assertEquals(1, calls.get())
	}

	@Test fun realIoFailureStillHasNetworkClassification() = runTest {
		val original = IOException("Connection lost")
		try {
			TranslationHttpClient(fixture { throw original }).execute(request) {}
			fail("Network failure returned a response")
		} catch (e: TranslateException.Network) {
			// Coroutine stack-trace recovery may copy the IOException across the async boundary.
			assertTrue(e.cause is IOException)
			assertEquals(original.message, e.cause?.message)
		}
	}

	@Test fun cancellationAtRequestGateDoesNotSendRequestOrBecomeNetworkError() = runTest {
		val entered = CompletableDeferred<Unit>()
		val calls = AtomicInteger()
		val client = TranslationHttpClient(fixture { chain ->
			calls.incrementAndGet()
			response(chain, 200, "translated")
		})
		val job = launch {
			client.execute(request) {
				entered.complete(Unit)
				awaitCancellation()
			}
			fail("Cancelled operation returned")
		}
		entered.await()
		job.cancelAndJoin()
		assertEquals(0, calls.get())
	}

	private fun fixture(terminal: Interceptor) = OkHttpClient.Builder()
		.addInterceptor(RateLimitInterceptor())
		.addInterceptor(terminal)
		.build()

	private fun response(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
		.request(chain.request())
		.protocol(Protocol.HTTP_1_1)
		.code(code)
		.message("Fixture response")
		.header("Retry-After", "0")
		.body(body.toResponseBody())
		.build()
}
