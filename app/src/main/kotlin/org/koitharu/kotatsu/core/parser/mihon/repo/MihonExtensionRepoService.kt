package org.koitharu.kotatsu.core.parser.mihon.repo

import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionRepoService @Inject constructor(
	@MangaHttpClient private val httpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	private val protoBuf = ProtoBuf

	suspend fun resolveRepo(indexUrl: String): ResolveResult {
		val normalizedIndexUrl = normalizeIndexUrl(indexUrl) ?: return ResolveResult.InvalidUrl
		val baseUrl = normalizedIndexUrl.removeSuffix("/index.min.json")
			.removeSuffix("/index.json")
			.removeSuffix("/index.pb")
		val repo = fetchRepoDetails(baseUrl) ?: return ResolveResult.InvalidRepo
		return ResolveResult.Success(repo)
	}

	suspend fun fetchExtensions(repo: MihonExtensionRepo): List<MihonAvailableExtension> {
		return loadEntries(repo, indexUrlFor(repo), depth = 0)
			.sortedBy { it.name.lowercase() }
	}

	fun getApkUrl(extension: MihonAvailableExtension): String {
		val apkName = extension.apkName
		if (apkName.startsWith("http://") || apkName.startsWith("https://")) {
			return apkName
		}
		return "${extension.repo.baseUrl}/apk/$apkName"
	}

	private suspend fun loadEntries(
		repo: MihonExtensionRepo,
		url: String,
		depth: Int,
	): List<MihonAvailableExtension> {
		if (depth > MAX_INDEX_HOPS) {
			return emptyList()
		}
		val bytes = fetchBytes(url) ?: return emptyList()
		return when (bytes.firstOrNull()) {
			OPEN_BRACKET -> {
				// Legacy flat index.min.json array.
				val entries = json.decodeFromString<List<MihonExtensionIndexEntryDto>>(bytes.decodeToString())
				if (entries.isTombstone()) {
					// The repo's legacy URL was replaced with an "Outdated App" marker; follow its
					// repo.json -> index_v2 pointer instead of presenting the placeholder rows.
					followLegacyPointer(repo, url, depth)
				} else {
					entries.mapNotNull { dto -> dto.toAvailableExtension(repo) }
				}
			}

			OPEN_BRACE -> {
				// Either a legacy repo.json pointer or a store-shaped JSON index.
				val text = bytes.decodeToString()
				val pointer = runCatching { json.decodeFromString<MihonExtensionRepoMetaResponse>(text) }.getOrNull()
				val store = runCatching { json.decodeFromString<NetworkExtensionStoreJson>(text) }.getOrNull()

				if (store != null && store.extensionList?.extensions?.isNotEmpty() == true) {
					store.toEntries(repo)
				} else if (store != null && !store.extensionListUrl.isNullOrBlank()) {
					loadEntries(repo, store.extensionListUrl, depth + 1)
				} else {
					val explicit = runCatching {
						json.decodeFromString<MihonStoreIndexPointer>(text)
					}.getOrNull()?.indexV2
					if (explicit != null) {
						loadEntries(repo, explicit, depth + 1)
					} else if (pointer != null) {
						// A bare repo.json was requested as the index: locate its index file by convention.
						loadEntries(repo, defaultIndexCandidates(url), depth + 1)
					} else {
						emptyList()
					}
				}
			}

			null -> emptyList()

			else -> {
				// Protobuf (`index.pb`) — same store shape, binary-encoded.
				val store = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) }.getOrNull()
					?: return emptyList()
				when {
					store.extensionList != null -> store.extensionList.extensions.mapNotNull {
						it.toAvailableExtension(repo)
					}

					!store.extensionListUrl.isNullOrBlank() -> loadEntries(repo, store.extensionListUrl, depth + 1)
					else -> emptyList()
				}
			}
		}
	}

	private suspend fun followLegacyPointer(repo: MihonExtensionRepo, url: String, depth: Int): List<MihonAvailableExtension> {
		// repo.baseUrl already ends with the repo root; attempt repo.json -> index_v2 hop.
		val repoJsonUrl = "${repo.baseUrl}/repo.json"
		val repoJsonBytes = fetchBytes(repoJsonUrl) ?: return emptyList()
		val pointer = runCatching {
			json.decodeFromString<MihonStoreIndexPointer>(repoJsonBytes.decodeToString())
		}.getOrNull() ?: return emptyList()
		val next = pointer.indexV2 ?: "${repo.baseUrl}/index.min.json"
		if (next == url) {
			return emptyList() // cycle guard
		}
		return loadEntries(repo, next, depth + 1)
	}

	private fun defaultIndexCandidates(baseUrl: String): String {
		val root = baseUrl.removeSuffix("/repo.json")
		return "$root/index.json"
	}

	private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
		runCatching {
			httpClient.newCall(
				Request.Builder()
					.url(url)
					.build(),
			).awaitSuccess().use { response ->
				response.body.bytes().gunzipIfNeeded()
			}
		}.getOrNull()?.takeIf { it.isNotEmpty() }
	}

	private fun ByteArray.gunzipIfNeeded(): ByteArray {
		return if (size >= 2 && this[0] == GZIP_MAGIC_0 && this[1] == GZIP_MAGIC_1) {
			runCatching { GZIPInputStream(inputStream()).use { it.readBytes() } }.getOrDefault(this)
		} else {
			this
		}
	}

	private fun List<MihonExtensionIndexEntryDto>.isTombstone(): Boolean {
		// The keiyoushi 2026-07-28 legacy index flip leaves exactly two placeholder rows
		// ("Outdated App", "Update to Mihon 0.20.1+") whose packages are the stub extensions.
		return size <= 2 && all { dto ->
			dto.pkg == TOMBSTONE_KEIYOUSHI_PKG || dto.pkg == TOMBSTONE_MIHON_PKG
		}
	}

	private fun NetworkExtensionStoreJson.toEntries(repo: MihonExtensionRepo): List<MihonAvailableExtension> {
		return extensionList?.extensions.orEmpty().mapNotNull { it.toAvailableExtension(repo) }
	}

	private fun MihonExtensionIndexEntryDto.toAvailableExtension(repo: MihonExtensionRepo): MihonAvailableExtension? {
		val libVersion = MihonExtensionPackageUtil.parseLibVersion(version) ?: return null
		if (!MihonExtensionPackageUtil.isSupportedLibVersion(libVersion)) {
			return null
		}
		return MihonAvailableExtension(
			repo = repo,
			name = name.removePrefix("Tachiyomi: ").trim(),
			pkgName = pkg,
			versionName = version,
			versionCode = code,
			libVersion = libVersion,
			lang = lang,
			isNsfw = nsfw == 1,
			sources = sources.orEmpty().map { source ->
				MihonAvailableExtensionSource(
					id = source.id,
					lang = source.lang,
					name = source.name,
					baseUrl = source.baseUrl,
				)
			},
			apkName = apk,
			iconUrl = "${repo.baseUrl}/icon/$pkg.png",
		)
	}

	private fun indexUrlFor(repo: MihonExtensionRepo): String {
		val baseUrl = repo.baseUrl
		return when {
			baseUrl.endsWith(".json") || baseUrl.endsWith(".pb") -> baseUrl
			repo.isStoreFormat -> "$baseUrl/index.json"
			else -> "$baseUrl/index.min.json"
		}
	}

	private suspend fun fetchRepoDetails(baseUrl: String): MihonExtensionRepo? {
		val body = fetchBytes("$baseUrl/repo.json")?.decodeToString() ?: return null
		return runCatching { json.decodeFromString<MihonExtensionRepoMetaResponse>(body).toRepo(baseUrl) }
			.getOrNull()
	}

	private fun normalizeIndexUrl(value: String): String? {
		return value.trim()
			.toHttpUrlOrNull()
			?.toString()
			?.takeIf { it.matches(REPO_URL_REGEX) }
	}

	sealed interface ResolveResult {
		data class Success(val repo: MihonExtensionRepo) : ResolveResult
		data object InvalidUrl : ResolveResult
		data object InvalidRepo : ResolveResult
	}

	private companion object {
		val REPO_URL_REGEX = """^https://.*/index\.(?:min\.json|json|pb)$""".toRegex()
		const val OPEN_BRACKET: Byte = 91 // '[' — legacy JSON array index
		const val OPEN_BRACE: Byte = 123 // '{' — JSON object (repo.json or store); else protobuf
		const val MAX_INDEX_HOPS = 3
		const val TOMBSTONE_KEIYOUSHI_PKG = "eu.kanade.tachiyomi.extension.all.keiyoushi"
		const val TOMBSTONE_MIHON_PKG = "eu.kanade.tachiyomi.extension.all.mihon"
		const val GZIP_MAGIC_0: Byte = 0x1f.toByte()
		const val GZIP_MAGIC_1: Byte = 0x8b.toByte()
	}
}
