package org.koitharu.kotatsu.core.parser.mihon.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class MihonExtensionRepo(
	val baseUrl: String,
	val name: String,
	val shortName: String? = null,
	val website: String,
	val signingKeyFingerprint: String,
	/**
	 * True when this repo serves the TachiyomiX 1.6 / Mihon #3349 "extension store" document
	 * (a JSON or protobuf object, possibly gzip-compressed, with `extensionList` /
	 * `extensionListUrl`) instead of a legacy `[ ... ]` `index.min.json` array. Default false so
	 * persisted 1.4-era repos keep decoding without a migration.
	 */
	val isStoreFormat: Boolean = false,
)

data class MihonAvailableExtension(
	val repo: MihonExtensionRepo,
	val name: String,
	val pkgName: String,
	val versionName: String,
	val versionCode: Long,
	val libVersion: Double,
	val lang: String,
	val isNsfw: Boolean,
	val sources: List<MihonAvailableExtensionSource>,
	val apkName: String,
	val iconUrl: String,
)

data class MihonAvailableExtensionSource(
	val id: Long,
	val lang: String,
	val name: String,
	val baseUrl: String,
)

data class MihonRepoExtensionDescriptor(
	val extension: MihonAvailableExtension,
	val installedVersionName: String? = null,
	val installedVersionCode: Long? = null,
	val installedLibVersion: Double? = null,
	val isInstalledPrivately: Boolean = false,
	val isInstalledExternally: Boolean = false,
	val hasUpdate: Boolean = false,
)

@Serializable
internal data class MihonExtensionRepoMetaResponse(
	val meta: MihonExtensionRepoMetaDto,
)

/** A store-shaped `repo.json` that points at the canonical index via `index_v2`. */
@Serializable
internal data class MihonStoreIndexPointer(
	@SerialName("index_v2") val indexV2: String? = null,
)

@Serializable
internal data class MihonExtensionRepoMetaDto(
	val name: String,
	val shortName: String? = null,
	val website: String,
	val signingKeyFingerprint: String,
)

@Serializable
internal data class MihonExtensionIndexEntryDto(
	val name: String,
	val pkg: String,
	val apk: String,
	val lang: String,
	val code: Long,
	val version: String,
	val nsfw: Int,
	val sources: List<MihonExtensionIndexSourceDto>? = null,
)

@Serializable
internal data class MihonExtensionIndexSourceDto(
	val id: Long,
	val lang: String,
	val name: String,
	val baseUrl: String,
)

internal fun MihonExtensionRepoMetaResponse.toRepo(baseUrl: String): MihonExtensionRepo {
	return MihonExtensionRepo(
		baseUrl = baseUrl,
		name = meta.name,
		shortName = meta.shortName,
		website = meta.website,
		signingKeyFingerprint = meta.signingKeyFingerprint,
	)
}

// --- Newer "extension store" index format (TachiyomiX 1.6 / Mihon #3349+): a single object served
// as JSON or protobuf (index.pb), optionally gzip-compressed, optionally with the extension list in
// a separate `extensionListUrl`. Decoded onto the existing `MihonAvailableExtension` model. ---

@Serializable
internal data class NetworkExtensionStore(
	@ProtoNumber(1) val name: String = "",
	@ProtoNumber(2) val badgeLabel: String = "",
	@ProtoNumber(3) val signingKey: String = "",
	@ProtoNumber(4) val contact: Contact? = null,
	@ProtoNumber(101) val extensionList: ExtensionList? = null,
	@ProtoNumber(102) val extensionListUrl: String? = null,
) {
	@Serializable
	data class Contact(
		@ProtoNumber(1) val website: String = "",
		@ProtoNumber(2) val discord: String? = null,
	)

	@Serializable
	data class ExtensionList(
		@ProtoNumber(1) val extensions: List<Extension> = emptyList(),
	)

	@Serializable
	data class Extension(
		@ProtoNumber(1) val name: String = "",
		@ProtoNumber(2) val packageName: String = "",
		@ProtoNumber(3) val resources: Resources = Resources(),
		/** e.g. "1.4" / "1.6" — a string in this schema, even in protobuf. */
		@ProtoNumber(4) val extensionLib: String = "",
		/** JSON encodes this as a string ("4"); protobuf as a varint. Read as Long here — the JSON
		 *  decoder is configured with `isLenient`-style coercion via @Serializable on String
		 *  fallbacks elsewhere; for Kaisoku's JSON path we decode a string-tolerant DTO below. */
		@ProtoNumber(5) val versionCode: Long = 0,
		@ProtoNumber(6) val versionName: String = "",
		@ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
		@ProtoNumber(8) val sources: List<Source> = emptyList(),
	)

	@Serializable
	data class Resources(
		@ProtoNumber(1) val apkUrl: String = "",
		@ProtoNumber(2) val iconUrl: String = "",
		/** keiyoushi-only: an additional JVM jar that no Android host currently consumes. */
		@ProtoNumber(3) val jarUrl: String? = null,
	)

	@Serializable
	data class Source(
		@ProtoNumber(1) val id: Long = 0,
		@ProtoNumber(2) val name: String = "",
		@ProtoNumber(3) val language: String = "",
		@ProtoNumber(4) val homeUrl: String = "",
		@ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
		@ProtoNumber(7) val message: String? = null,
	)

	@Serializable
	enum class ContentWarning {
		@ProtoNumber(0) @JsonNames("CONTENT_WARNING_UNSPECIFIED") UNSPECIFIED,
		@ProtoNumber(1) @JsonNames("CONTENT_WARNING_SAFE") SAFE,
		@ProtoNumber(2) @JsonNames("CONTENT_WARNING_MIXED") MIXED,
		@ProtoNumber(3) @JsonNames("CONTENT_WARNING_NSFW") NSFW,
	}
}

/** Stringy `versionCode` variant for the JSON index (keiyoushi serves `"4"`, not `4`). */
@Serializable
internal data class NetworkExtensionStoreJson(
	val name: String = "",
	val badgeLabel: String = "",
	val signingKey: String = "",
	val contact: NetworkExtensionStore.Contact? = null,
	val extensionList: ExtensionListJson? = null,
	val extensionListUrl: String? = null,
) {
	@Serializable
	data class ExtensionListJson(val extensions: List<ExtensionJson> = emptyList())

	@Serializable
	data class ExtensionJson(
		val name: String = "",
		val packageName: String = "",
		val resources: NetworkExtensionStore.Resources = NetworkExtensionStore.Resources(),
		val extensionLib: String = "",
		val versionCode: String = "",
		val versionName: String = "",
		val contentWarning: NetworkExtensionStore.ContentWarning = NetworkExtensionStore.ContentWarning.UNSPECIFIED,
		val sources: List<SourceJson> = emptyList(),
	)

	/** JSON sources carry their id as a string, e.g. "6289731484943315811"; parsed to Long here. */
	@Serializable
	data class SourceJson(
		val id: String = "",
		val name: String = "",
		val language: String = "",
		val homeUrl: String = "",
		val mirrorUrls: List<String> = emptyList(),
		val message: String? = null,
	)
}

internal fun NetworkExtensionStore.Extension.toAvailableExtension(repo: MihonExtensionRepo): MihonAvailableExtension? {
	if (packageName.isBlank()) {
		return null
	}
	return toAvailableExtensionCommon(
		name = name,
		packageName = packageName,
		resources = resources,
		extensionLib = extensionLib,
		versionCode = versionCode,
		versionName = versionName,
		contentWarning = contentWarning,
		sources = sources,
		repo = repo,
	)
}

internal fun NetworkExtensionStoreJson.ExtensionJson.toAvailableExtension(repo: MihonExtensionRepo): MihonAvailableExtension? {
	if (packageName.isBlank()) {
		return null
	}
	return toAvailableExtensionCommon(
		name = name,
		packageName = packageName,
		resources = resources,
		extensionLib = extensionLib,
		versionCode = versionCode.toLongOrNull() ?: 0L,
		versionName = versionName,
		contentWarning = contentWarning,
		sources = sources.map { it.toSource() },
		repo = repo,
	)
}

private fun NetworkExtensionStoreJson.SourceJson.toSource(): NetworkExtensionStore.Source {
	return NetworkExtensionStore.Source(
		id = id.toLongOrNull() ?: 0L,
		name = name,
		language = language,
		homeUrl = homeUrl,
		mirrorUrls = mirrorUrls,
		message = message,
	)
}

private fun toAvailableExtensionCommon(
	name: String,
	packageName: String,
	resources: NetworkExtensionStore.Resources,
	extensionLib: String,
	versionCode: Long,
	versionName: String,
	contentWarning: NetworkExtensionStore.ContentWarning,
	sources: List<NetworkExtensionStore.Source>,
	repo: MihonExtensionRepo,
): MihonAvailableExtension? {
	val libVersion = extensionLib.toDoubleOrNull() ?: return null
	val langs = sources.map { it.language }.filter { it.isNotBlank() }.toSet()
	val displayLang = when (langs.size) {
		0 -> "all"
		1 -> langs.first()
		else -> "all"
	}
	return MihonAvailableExtension(
		repo = repo,
		name = name.removePrefix("Tachiyomi: ").trim(),
		pkgName = packageName,
		versionName = versionName,
		versionCode = versionCode,
		libVersion = libVersion,
		lang = displayLang,
		isNsfw = contentWarning >= NetworkExtensionStore.ContentWarning.MIXED,
		sources = sources.map { source ->
			MihonAvailableExtensionSource(
				id = source.id,
				lang = source.language,
				name = source.name,
				baseUrl = source.homeUrl,
			)
		},
		// Absolute on newer stores; resolveApkUrl passes absolute URLs through unchanged.
		apkName = resources.apkUrl,
		iconUrl = resources.iconUrl,
	)
}
