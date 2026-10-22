package org.koitharu.kotatsu.core.parser.mihon.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MihonIndexPointerTest {

    private val base = "https://repo.example/extensions"
    private val repo = MihonExtensionRepo(base, "Saved repository", "SAVE", "https://repo.example", "trusted-key")
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun authoritativePointerReplacesRealButStaleLegacyRows() = runBlocking {
        val service = service { url ->
            when (url) {
                "$base/repo.json" -> json("""{"index_v2":"current/index.json"}""")
                "$base/current/index.json" -> json(legacy("new"))
                "$base/index.min.json" -> json(legacy("stale"))
                else -> missing()
            }
        }
        val extension = service.fetchExtensions(repo.copy(indexUrl = "$base/index.min.json")).single()
        assertEquals("eu.kanade.tachiyomi.extension.en.new", extension.pkgName)
        assertEquals(listOf("$base/repo.json", "$base/current/index.json"), requests)
        assertEquals(repo.baseUrl, extension.repo.baseUrl)
        assertEquals(repo.signingKeyFingerprint, extension.repo.signingKeyFingerprint)
        assertEquals("$base/apk/new.apk", service.getApkUrl(extension))
    }

    @Test
    fun savedRepositoryWithoutIndexUsesRelativeProtobufPointerAndRetainsIdentity() = runBlocking {
        val bytes = ProtoBuf.encodeToByteArray(NetworkExtensionStore(
            name = "Different metadata must not replace the saved signer",
            signingKey = "different-key",
            extensionList = NetworkExtensionStore.ExtensionList(listOf(NetworkExtensionStore.Extension(
                name = "Example", packageName = "eu.kanade.tachiyomi.extension.en.example",
                resources = NetworkExtensionStore.Resources(apkUrl = "https://cdn.example/example.apk"),
                extensionLib = "1.6", versionCode = 9, versionName = "1.6.9",
                sources = listOf(NetworkExtensionStore.Source(id = 42, name = "Example", language = "en")),
            ))),
        ))
        val service = service { url ->
            when (url) {
                "$base/repo.json" -> json("""{"index_v2":"../current/index.pb"}""")
                "https://repo.example/current/index.pb" -> 200 to bytes
                else -> missing()
            }
        }
        val extension = service.fetchExtensions(repo).single()
        assertSame(repo, extension.repo)
        assertEquals(42L, extension.sources.single().id)
        assertEquals("https://cdn.example/example.apk", service.getApkUrl(extension))
        assertEquals(listOf("$base/repo.json", "https://repo.example/current/index.pb"), requests)
    }

    @Test
    fun explicitModernIndexDoesNotLookUpLegacyMetadata() = runBlocking {
        val modern = "https://cdn.example/index.json"
        val service = service { url -> if (url == modern) json(legacy("modern")) else missing() }
        assertEquals("modern", service.fetchExtensions(repo.copy(indexUrl = modern)).single().name)
        assertEquals(listOf(modern), requests)
    }

    @Test
    fun optionalMetadataFailuresDoNotHideAnUnmigratedIndex() = runBlocking {
        for (metadata in listOf(missing(), json("not-json"), json("""{"index_v2":"  "}"""), 503 to byteArrayOf())) {
            requests.clear()
            val service = service { url ->
                when (url) {
                    "$base/repo.json" -> metadata
                    "$base/index.min.json" -> json(legacy("legacy"))
                    else -> missing()
                }
            }
            assertEquals("legacy", service.fetchExtensions(repo).single().name)
            assertEquals(listOf("$base/repo.json", "$base/index.min.json"), requests)
        }
    }

    @Test
    fun tombstoneFallbackReadsOptionalMetadataOnlyOncePerRefresh() = runBlocking {
        val service = service { url ->
            when (url) {
                "$base/index.min.json" -> json(legacy("keiyoushi", "all"))
                "$base/index.json" -> json(legacy("current"))
                else -> missing()
            }
        }
        assertEquals("current", service.fetchExtensions(repo).single().name)
        assertEquals(listOf("$base/repo.json", "$base/index.min.json", "$base/index.json"), requests)
    }

    @Test
    fun missingOrRateLimitedAuthoritativeIndexDoesNotFallBackToStaleRows() = runBlocking {
        for (status in listOf(404, 429, 503)) {
            requests.clear()
            val service = service { url ->
                when (url) {
                    "$base/repo.json" -> json("""{"index_v2":"current/index.json"}""")
                    "$base/current/index.json" -> status to byteArrayOf()
                    else -> json(legacy("stale"))
                }
            }
            try {
                service.fetchExtensions(repo)
                fail("An unavailable authoritative index must not present stale rows as a fresh catalog")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains(if (status == 404) "missing" else status.toString()))
            }
            assertEquals(listOf("$base/repo.json", "$base/current/index.json"), requests)
        }
    }

    @Test
    fun emptyAuthoritativeIndexIsARealEmptyCatalog() = runBlocking {
        val service = service { url ->
            when (url) {
                "$base/repo.json" -> json("""{"index_v2":"index.json"}""")
                "$base/index.json" -> json("[]")
                else -> json(legacy("stale"))
            }
        }
        assertTrue(service.fetchExtensions(repo).isEmpty())
        assertEquals(listOf("$base/repo.json", "$base/index.json"), requests)
    }

    @Test
    fun metadataIsRecheckedOnTheNextRefresh() = runBlocking {
        var version = "first"
        val service = service { url ->
            if (url == "$base/repo.json") json("""{"index_v2":"$version/index.json"}""") else json(legacy(version))
        }
        assertEquals("first", service.fetchExtensions(repo).single().name)
        version = "second"
        assertEquals("second", service.fetchExtensions(repo).single().name)
        assertEquals(listOf("$base/repo.json", "$base/first/index.json", "$base/repo.json", "$base/second/index.json"), requests)
    }

    @Test
    fun indexPointerCyclesFailWithinTheExistingHopLimit() = runBlocking {
        val service = service { json("""{"index_v2":"index.json"}""") }
        try {
            service.fetchExtensions(repo)
            fail("Expected bounded pointer traversal")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("too many"))
        }
        assertEquals(5, requests.size) // one metadata lookup, at most four index documents
        assertEquals(1, requests.count { it == "$base/repo.json" })
    }

    @Test
    fun cancellingMetadataLookupDoesNotStartAFallbackRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val service = service {
            started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            missing()
        }
        val refresh = async { service.fetchExtensions(repo) }
        try {
            withTimeout(3_000) {
                started.await()
                refresh.cancelAndJoin()
            }
            assertTrue(refresh.isCancelled)
            assertEquals(listOf("$base/repo.json"), requests)
        } finally {
            release.countDown()
            refresh.cancelAndJoin()
        }
    }

    private fun service(reply: (String) -> Pair<Int, ByteArray>) =
        MihonExtensionRepoService(OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            requests.add(url)
            val (code, body) = reply(url)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody()).build()
        }.build())

    private fun legacy(name: String, lang: String = "en") = """[{
        "name":"$name", "pkg":"eu.kanade.tachiyomi.extension.$lang.$name", "apk":"$name.apk",
        "lang":"$lang", "code":9, "version":"1.4.9", "nsfw":0, "sources":[]
    }]""".trimIndent()

    private fun json(body: String) = 200 to body.toByteArray()
    private fun missing() = 404 to byteArrayOf()
}
