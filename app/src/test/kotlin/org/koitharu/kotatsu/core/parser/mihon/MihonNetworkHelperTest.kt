package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class MihonNetworkHelperTest {

	private val request = Request.Builder().url("https://extension.invalid/catalog").build()

	@Test fun extensionCanInsertItsInterceptorAfterTheDefaultExceptionHandler() {
		val base = OkHttpClient.Builder().addInterceptor { chain -> response(chain) }.build()
		val helper = MihonNetworkHelper(base) { "Kaisoku fixture" }
		val builder = helper.client.newBuilder()
		// Current extension helpers locate this named anchor in the default client.
		val anchor = builder.interceptors().indexOfFirst {
			it.javaClass.simpleName == "UncaughtExceptionInterceptor"
		}
		assertEquals(0, anchor)
		var extensionRan = false
		builder.interceptors().add(anchor + 1, Interceptor { chain ->
			extensionRan = true
			chain.proceed(chain.request())
		})
		builder.build().newCall(request).execute().use { assertEquals("catalog", it.body.string()) }
		assertTrue(extensionRan)
		assertFalse(base.interceptors.any { it is UncaughtExceptionInterceptor })
	}

	@Test fun sourceRuntimeFailureBecomesAnIoFailureWithItsCause() {
		val failure = IllegalStateException("Extension parser failed")
		val base = OkHttpClient.Builder().addInterceptor { throw failure }.build()
		val helper = MihonNetworkHelper(base) { "fixture" }
		val error = assertThrows(IOException::class.java) { helper.client.newCall(request).execute() }
		assertSame(failure, error.cause)
	}

	@Test fun existingIoFailureIsPreserved() {
		val failure = IOException("Connection lost")
		val base = OkHttpClient.Builder().addInterceptor { throw failure }.build()
		val helper = MihonNetworkHelper(base) { "fixture" }
		assertSame(failure, assertThrows(IOException::class.java) { helper.client.newCall(request).execute() })
	}

	@Test fun fatalErrorsAreNotHidden() {
		val failure = LinkageError("Missing extension API")
		val base = OkHttpClient.Builder().addInterceptor { throw failure }.build()
		val helper = MihonNetworkHelper(base) { "fixture" }
		assertSame(failure, assertThrows(LinkageError::class.java) { helper.client.newCall(request).execute() })
	}

	@Test fun configuredNetworkingAndUserAgentAreRetained() {
		val base = OkHttpClient.Builder().readTimeout(42, TimeUnit.SECONDS).build()
		val helper = MihonNetworkHelper(base) { "Configured user agent" }
		assertSame(base.cookieJar, helper.client.cookieJar)
		assertSame(base.dns, helper.client.dns)
		assertSame(base.proxySelector, helper.client.proxySelector)
		assertSame(base.connectionPool, helper.client.connectionPool)
		assertEquals(base.readTimeoutMillis, helper.client.readTimeoutMillis)
		assertEquals("Configured user agent", helper.defaultUserAgentProvider())
	}

	private fun response(chain: Interceptor.Chain): Response = Response.Builder()
		.request(chain.request())
		.protocol(Protocol.HTTP_1_1)
		.code(200)
		.message("OK")
		.body("catalog".toResponseBody())
		.build()
}
