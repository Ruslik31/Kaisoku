package org.koitharu.kotatsu.core.parser.mihon.repo

import eu.kanade.tachiyomi.network.await
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
import java.io.IOException
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
		val repo = if (normalizedIndexUrl.endsWith("/index.min.json")) {
			fetchRepoDetails(baseUrl)?.copy(indexUrl = normalizedIndexUrl)
		} else {
			val bytes = fetchBytes(normalizedIndexUrl) ?: return ResolveResult.InvalidRepo
			when (bytes.firstOrNull()) {
				OPEN_BRACE -> {
					val store = json.decodeFromString<NetworkExtensionStoreJson>(bytes.decodeToString())
					storeRepo(baseUrl, normalizedIndexUrl, store.name, store.badgeLabel, store.signingKey, store.contact)
				}
				OPEN_BRACKET -> fetchRepoDetails(baseUrl)?.copy(indexUrl = normalizedIndexUrl)
				else -> {
					val store = protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
					storeRepo(baseUrl, normalizedIndexUrl, store.name, store.badgeLabel, store.signingKey, store.contact)
				}
			}
		} ?: return ResolveResult.InvalidRepo
		return ResolveResult.Success(repo)
	}

	private fun storeRepo(
		baseUrl: String,
		indexUrl: String,
		name: String,
		badge: String,
		key: String,
		contact: NetworkExtensionStore.Contact?,
	): MihonExtensionRepo? {
		if (name.isBlank() || key.isBlank()) return null
		return MihonExtensionRepo(
			baseUrl = baseUrl,
			name = name,
			shortName = badge.takeIf(String::isNotBlank),
			website = contact?.website.orEmpty(),
			signingKeyFingerprint = key,
			isStoreFormat = true,
			indexUrl = indexUrl,
		)
	}

	suspend fun fetchExtensions(repo: MihonExtensionRepo): List<MihonAvailableExtension> {
		val entries = if (repo.indexUrl != null && !repo.indexUrl.endsWith("/index.min.json")) {
			loadEntries(repo, repo.indexUrl, depth = 0)
		} else {
			// Repositories saved by older versions did not retain the supplied modern index URL.
			loadFirstAvailableIndex(
				repo,
				(listOf(repo.indexUrl ?: indexUrlFor(repo)) + legacyModernIndexCandidates(repo.baseUrl)).distinct(),
				0,
			)
		}
		return entries.sortedBy { it.name.lowercase() }
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
		suppliedBytes: ByteArray? = null,
	): List<MihonAvailableExtension> {
		if (depth > MAX_INDEX_HOPS) {
			throw IOException("Extension repository index redirects too many times: $url")
		}
		val bytes = suppliedBytes ?: fetchBytes(url)
			?: throw IOException("Extension repository index is missing or empty: $url")
		return when (bytes.firstOrNull()) {
			OPEN_BRACKET -> {
				// Legacy flat index.min.json array.
				val entries = json.decodeFromString<List<MihonExtensionIndexEntryDto>>(bytes.decodeToString())
				if (entries.isLegacyOutdatedPlaceholderIndex()) {
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
					loadEntries(repo, resolveRepoIndexUrl(repo, store.extensionListUrl), depth + 1)
				} else {
					val explicit = runCatching {
						json.decodeFromString<MihonStoreIndexPointer>(text)
					}.getOrNull()?.indexV2
					if (explicit != null) {
						loadEntries(repo, resolveRepoIndexUrl(repo, explicit), depth + 1)
					} else if (pointer != null) {
						// A bare repo.json was requested as the index: locate its index file by convention.
						loadFirstAvailableIndex(repo, legacyModernIndexCandidates(repo.baseUrl), depth + 1)
					} else {
						emptyList()
					}
				}
			}

			null -> emptyList()

			else -> {
				// Protobuf (`index.pb`) — same store shape, binary-encoded.
				val store = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) }
					.getOrElse { throw IOException("Could not decode extension repository index", it) }
				when {
					store.extensionList != null -> store.extensionList.extensions.mapNotNull {
						it.toAvailableExtension(repo)
					}

					!store.extensionListUrl.isNullOrBlank() -> loadEntries(
						repo,
						resolveRepoIndexUrl(repo, store.extensionListUrl),
						depth + 1,
					)
					else -> emptyList()
				}
			}
		}
	}

	private suspend fun followLegacyPointer(repo: MihonExtensionRepo, url: String, depth: Int): List<MihonAvailableExtension> {
		// Some legacy repositories publish a pointer, while others only expose the new index
		// files. A missing repo.json must not hide a valid index.json or index.pb.
		val pointer = fetchBytes("${repo.baseUrl}/repo.json")?.let { bytes ->
			runCatching { json.decodeFromString<MihonStoreIndexPointer>(bytes.decodeToString()).indexV2 }
				.getOrNull()
		}
		val candidates = legacyModernIndexCandidates(repo.baseUrl, pointer)
			.map { resolveRepoIndexUrl(repo, it) }
			.filterNot { it == url }
		return loadFirstAvailableIndex(repo, candidates, depth + 1)
	}

	private suspend fun loadFirstAvailableIndex(
		repo: MihonExtensionRepo,
		candidates: List<String>,
		depth: Int,
	): List<MihonAvailableExtension> {
		for (candidate in candidates) {
			val bytes = fetchBytes(candidate) ?: continue
			return loadEntries(repo, candidate, depth, suppliedBytes = bytes)
		}
		throw IOException("Extension repository index is missing or empty: ${repo.baseUrl}")
	}

	private fun resolveRepoIndexUrl(repo: MihonExtensionRepo, value: String): String {
		return resolveRepoIndexUrlFromBase(repo.baseUrl, value)
	}

	private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
		httpClient.newCall(
			Request.Builder()
				.url(url)
				.build(),
		).await().use { response ->
			if (response.code == 404) return@withContext null
			if (!response.isSuccessful) throw IOException("Repository request failed with HTTP ${response.code}: $url")
			response.body.bytes().gunzipIfNeeded().normalizeIndexJson().takeIf { it.isNotEmpty() }
		}
	}

	private fun ByteArray.gunzipIfNeeded(): ByteArray {
		return if (size >= 2 && this[0] == GZIP_MAGIC_0 && this[1] == GZIP_MAGIC_1) {
			runCatching { GZIPInputStream(inputStream()).use { it.readBytes() } }
				.getOrElse { throw IOException("Could not decompress extension repository index", it) }
		} else {
			this
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

internal fun List<MihonExtensionIndexEntryDto>.isLegacyOutdatedPlaceholderIndex(): Boolean {
	// The keiyoushi legacy index flip leaves at most two placeholder rows whose packages
	// are the migration stubs rather than real extensions.
	return isNotEmpty() && size <= 2 && all { dto ->
		dto.pkg == "eu.kanade.tachiyomi.extension.all.keiyoushi" ||
			dto.pkg == "eu.kanade.tachiyomi.extension.all.mihon"
	}
}

internal fun legacyModernIndexCandidates(repoBaseUrl: String, pointer: String? = null): List<String> {
	val root = repoBaseUrl.trimEnd('/')
	return buildList {
		pointer?.takeIf(String::isNotBlank)?.let(::add)
		add("$root/index.json")
		add("$root/index.pb")
	}.distinct()
}

internal fun resolveRepoIndexUrlFromBase(repoBaseUrl: String, value: String): String {
	val base = repoBaseUrl.trimEnd('/') + "/"
	return base.toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
}

/** Strip JSON whitespace/BOM only when JSON is detected; protobuf often starts with 0x0a. */
internal fun ByteArray.normalizeIndexJson(): ByteArray {
	var start = if (size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()) 3 else 0
	while (start < size && this[start].toInt() in listOf(9, 10, 13, 32)) start++
	if (start == 0 || getOrNull(start)?.toInt() !in listOf(91, 123)) return this
	val candidate = copyOfRange(start, size)
	// A protobuf string length may itself equal '[' or '{'; do not strip its field tag.
	return if (runCatching { Json.parseToJsonElement(candidate.decodeToString()) }.isSuccess) candidate else this
}
