package org.koitharu.kotatsu.core.parser.mihon.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections

class MihonRepositoryResolutionTest {

	private val base = "https://cdn.jsdelivr.net/gh/yuzono/cursed-manga-repo@repo"
	// Captured 2026-09-28 from the same repo branch on raw.githubusercontent.com.
	// The fixture retains the upstream gzip encoding and field-501 jar URLs.
	private val index = checkNotNull(javaClass.getResourceAsStream("/mihon/cursed-index.pb")).use { it.readBytes() }

	private fun service(responses: Map<String, Pair<Int, ByteArray>>, requests: MutableList<String>) =
		MihonExtensionRepoService(OkHttpClient.Builder().addInterceptor { chain ->
			val url = chain.request().url.toString()
			requests.add(url)
			val (status, body) = responses[url] ?: (404 to byteArrayOf())
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(status).message("Fixture").body(body.toResponseBody()).build()
		}.build())

	@Test
	fun modernProtobufRepoNeedsNoLegacyMetadataAndKeepsItsIndexAfterPersistence() = runBlocking {
		val requests = Collections.synchronizedList(mutableListOf<String>())
		val service = service(mapOf("$base/index.pb" to (200 to index)), requests)
		val result = service.resolveRepo("$base/index.pb") as MihonExtensionRepoService.ResolveResult.Success
		val repo = Json.decodeFromString<MihonExtensionRepo>(Json.encodeToString(result.repo))
		assertEquals("Cursed Yūzōnō", repo.name)
		assertEquals(base, repo.baseUrl)
		assertEquals("$base/index.pb", repo.indexUrl)
		assertTrue(repo.signingKeyFingerprint.isNotBlank())
		val extensions = service.fetchExtensions(repo)
		assertTrue(extensions.any { it.name == "E-Hentai" && it.sources.isNotEmpty() })
		assertTrue(extensions.all { it.apkName.startsWith("https://") })
		assertEquals(listOf("$base/index.pb", "$base/index.pb"), requests)
	}

	@Test
	fun previouslySavedRepoRecoversWhenLegacyAndJsonIndexesAreMissing() = runBlocking {
		val requests = Collections.synchronizedList(mutableListOf<String>())
		val service = service(mapOf("$base/index.pb" to (200 to index)), requests)
		val repo = MihonExtensionRepo(base, "Cursed", null, "", "existing-key")
		assertTrue(service.fetchExtensions(repo).isNotEmpty())
		assertEquals(listOf("$base/index.min.json", "$base/index.json", "$base/index.pb"), requests)
	}

	@Test
	fun modernJsonRepoCanBeAddedWithoutRepoJson() = runBlocking {
		val requests = Collections.synchronizedList(mutableListOf<String>())
		val body = """{"name":"Modern","signingKey":"key","extensionList":{"extensions":[]}}""".toByteArray()
		val service = service(mapOf("$base/index.json" to (200 to body)), requests)
		val repo = (service.resolveRepo("$base/index.json") as MihonExtensionRepoService.ResolveResult.Success).repo
		assertTrue(service.fetchExtensions(repo).isEmpty())
		assertEquals(listOf("$base/index.json", "$base/index.json"), requests)
	}

	@Test
	fun explicitLegacyUrlAlsoRecoversWhenRetired() = runBlocking {
		val requests = Collections.synchronizedList(mutableListOf<String>())
		val service = service(mapOf("$base/index.pb" to (200 to index)), requests)
		val repo = MihonExtensionRepo(base, "Cursed", null, "", "key", indexUrl = "$base/index.min.json")
		assertTrue(service.fetchExtensions(repo).isNotEmpty())
		assertEquals("$base/index.pb", requests.last())
	}

	@Test
	fun missingExplicitIndexIsAnErrorRatherThanAnEmptyCatalog() = runBlocking {
		val requests = Collections.synchronizedList(mutableListOf<String>())
		val service = service(emptyMap(), requests)
		val repo = MihonExtensionRepo(base, "Cursed", null, "", "key", indexUrl = "$base/index.pb")
		try {
			service.fetchExtensions(repo)
			fail("Expected a missing-index error")
		} catch (e: IOException) {
			assertTrue(e.message.orEmpty().contains("index.pb"))
		}
	}

	@Test
	fun networkFailuresAreNotShownAsEmptyRepositories() = runBlocking {
		val requests = Collections.synchronizedList(mutableListOf<String>())
		val service = service(mapOf("$base/index.pb" to (429 to byteArrayOf())), requests)
		try {
			service.resolveRepo("$base/index.pb")
			fail("Expected the HTTP failure")
		} catch (e: IOException) {
			assertTrue(e.message.orEmpty().contains("429"))
		}
	}
}
