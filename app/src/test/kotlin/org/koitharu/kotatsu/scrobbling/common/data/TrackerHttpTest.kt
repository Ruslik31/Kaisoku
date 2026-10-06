package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListInterceptor
import org.koitharu.kotatsu.scrobbling.mangabaka.data.MangaBakaInterceptor
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser

class TrackerHttpTest {
    private fun storage() = ScrobblerStorage(MemoryPreferences(), MemoryPreferences(), MemoryPreferences(), ScrobblerService.MANGABAKA)
        .apply { accessToken = "fixture-token"; user = ScrobblerUser(1, "Fixture", null, ScrobblerService.MANGABAKA) }

    @Test fun rateLimitResponsePreservesRetryAfterAndRedactsTheCredential() = runBlocking {
        val store = storage()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limit")
                .header("Retry-After", "90").body("fixture-token in error".toResponseBody()).build()
        }.build()
        val error = runCatching { client.trackerCall(Request.Builder().url("https://example.org/api").build(), store) }.exceptionOrNull()
        assertTrue(error is TrackerHttpException)
        assertEquals(90_000L, (error as TrackerHttpException).retryAfterMillis)
        assertFalse(error.message.orEmpty().contains("fixture-token"))
    }

    @Test fun aLateResponseAfterLogoutCannotUpdateTheNewSessionAndIsClosed() = runBlocking {
        val store = storage(); val body = CloseBody()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            store.clear()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val error = runCatching { client.trackerCall(Request.Builder().url("https://example.org/api").build(), store) }.exceptionOrNull()
        assertTrue(error is TrackerSessionChangedException); assertTrue(body.closed)
    }

    @Test fun bearerInterceptorsUseTheRequestSnapshotInsteadOfALaterDynamicToken() = runBlocking {
        for (interceptor in listOf(AniListInterceptor { "wrong-token" }, MangaBakaInterceptor { "wrong-token" })) {
            val store = storage(); var authorization: String? = null
            val client = OkHttpClient.Builder().addInterceptor(interceptor).addInterceptor { chain ->
                authorization = chain.request().header("Authorization")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }.build()
            client.trackerCall(Request.Builder().url("https://example.org/api").build(), store).close()
            assertEquals("Bearer fixture-token", authorization)
        }
    }

    private class CloseBody : ResponseBody() {
        var closed = false
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 0
        override fun source(): okio.BufferedSource = okio.Buffer()
        override fun close() { closed = true; super.close() }
    }
}
