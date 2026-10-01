package org.koitharu.kotatsu.core.parser.mihon.repo

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MihonIndexFetchTest {
    private val repo = MihonExtensionRepo(
        baseUrl = "https://repo.example", name = "Example", shortName = null,
        website = "", signingKeyFingerprint = "test",
    )

    @Test fun missingIndexesAreErrorsInsteadOfEmptyCatalogs() = runBlocking {
        val service = service { 404 to "" }
        try {
            service.fetchExtensions(repo)
            fail("Missing indexes must not be a successful empty catalog")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("missing"))
        }
    }

    @Test fun genuineEmptyIndexDoesNotFallThroughToAnotherIndex() = runBlocking {
        val paths = mutableListOf<String>()
        val service = service { path -> paths.add(path); 200 to " \n[]" }
        assertTrue(service.fetchExtensions(repo).isEmpty())
        assertEquals(listOf("/index.min.json"), paths)
    }

    @Test fun missingLegacyIndexFallsBackToModernJson() = runBlocking {
        val service = service { path ->
            if (path == "/index.min.json") 404 to "" else 200 to "[]"
        }
        assertTrue(service.fetchExtensions(repo).isEmpty())
    }

    private fun service(reply: (String) -> Pair<Int, String>): MihonExtensionRepoService =
        MihonExtensionRepoService(OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = reply(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody()).build()
        }.build())
}
