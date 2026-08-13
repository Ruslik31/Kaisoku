package org.koitharu.kotatsu.core.parser.mihon

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

internal data class MihonInstalledExtensionPackage(
	val packageInfo: PackageInfo,
	val isPrivate: Boolean,
)

internal object MihonExtensionPackageUtil {

	const val EXTENSION_FEATURE = "tachiyomi.extension"
	const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
	const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
	const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
	const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"
	const val LIB_VERSION_MIN = 1.4
	const val LIB_VERSION_MAX = 1.6

	private val scanFlags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS

	@Suppress("DEPRECATION")
	private val packageQueryFlags = PackageManager.GET_META_DATA or
		PackageManager.GET_CONFIGURATIONS or
		PackageManager.GET_SIGNATURES or
		(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

	fun getInstalledPackages(pm: PackageManager): List<PackageInfo> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(scanFlags.toLong()))
		} else {
			@Suppress("DEPRECATION")
			pm.getInstalledPackages(scanFlags)
		}
	}

	fun getPackageInfoOrNull(pm: PackageManager, packageName: String): PackageInfo? {
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(packageQueryFlags.toLong()))
			} else {
				@Suppress("DEPRECATION")
				pm.getPackageInfo(packageName, packageQueryFlags)
			}
		} catch (_: PackageManager.NameNotFoundException) {
			null
		}
	}

	fun getPackageArchiveInfoOrNull(pm: PackageManager, apkFile: File): PackageInfo? {
		val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(packageQueryFlags.toLong()))
		} else {
			@Suppress("DEPRECATION")
			pm.getPackageArchiveInfo(apkFile.absolutePath, packageQueryFlags)
		}
		return info?.also { pkgInfo ->
			pkgInfo.applicationInfo?.fixBasePaths(apkFile.absolutePath)
		}
	}

	fun refreshPackageInfoIfNeeded(pm: PackageManager, pkgInfo: PackageInfo): PackageInfo {
		val needsRefresh = pkgInfo.applicationInfo?.metaData == null || pkgInfo.reqFeatures == null
		if (!needsRefresh) {
			return pkgInfo
		}
		return getPackageInfoOrNull(pm, pkgInfo.packageName) ?: pkgInfo
	}

	fun isMihonExtension(pkgInfo: PackageInfo): Boolean {
		val metaData = pkgInfo.applicationInfo?.metaData
		val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
		val hasMetadata = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
			metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
		val looksLikeExtension = pkgInfo.packageName.contains(".extension") ||
			pkgInfo.packageName.startsWith("eu.kanade.tachiyomi.") ||
			pkgInfo.packageName.startsWith("org.keiyoushi.")
		return hasFeature || (hasMetadata && looksLikeExtension)
	}

	fun parseLibVersion(versionName: String?): Double? {
		versionName ?: return null
		return runCatching {
			versionName.split('.').let { parts ->
				if (parts.size >= 2) {
					"${parts[0]}.${parts[1]}".toDouble()
				} else {
					parts[0].toDouble()
				}
			}
		}.getOrNull()
	}

	internal fun roundToOneDecimal(value: Double): Double = (value * 10.0).roundToInt() / 10.0

	/**
	 * Reads the TachiyomiX 1.6+ `tachiyomix.extensionLib` manifest metadata if present, otherwise
	 * falls back to parsing the leading `major.minor` of `versionName` (extensions-lib 1.4/1.5).
	 * The metadata value is read type-tolerantly because Android's `Bundle` narrows manifest
	 * `android:value="1.6"` to a Float and older manifest-toolchains can emit Double/String.
	 */
	fun readLibVersion(metaData: android.os.Bundle?, versionName: String?): Double? {
		metaData?.get(METADATA_EXTENSION_LIB)?.let { raw ->
			val parsed = when (raw) {
				is Float -> roundToOneDecimal(raw.toDouble()).takeUnless { it == 0.0 }
				is Double -> roundToOneDecimal(raw).takeUnless { it == 0.0 }
				is Number -> roundToOneDecimal(raw.toDouble()).takeUnless { it == 0.0 }
				is String -> roundToOneDecimal(raw.toDoubleOrNull() ?: return@let null).takeUnless { it == 0.0 }
				else -> null
			}
			if (parsed != null) {
				return parsed
			}
		}
		return parseLibVersion(versionName)
	}

	fun isSupportedLibVersion(libVersion: Double): Boolean {
		return libVersion in LIB_VERSION_MIN..LIB_VERSION_MAX
	}

	/**
	 * TachiyomiX 1.6+ NSFW/content-warning flag read: `tachiyomix.contentWarning` is the Int enum
	 * (UNSPECIFIED=0, SAFE=1, MIXED=2, NSFW=3) where MIXED and NSFW gate, OR'd with the legacy
	 * `tachiyomi.extension.nsfw` flag which has been emitted as Int, Boolean and "1"/"true" strings.
	 */
	fun readNsfwFlag(metaData: android.os.Bundle): Boolean {
		if (runCatching { metaData.getInt(METADATA_CONTENT_WARNING, 0) > 0 }.getOrDefault(false)) {
			return true
		}
		val legacy = metaData.get("tachiyomi.extension.nsfw")
		return when (legacy) {
			is Boolean -> legacy
			is Number -> legacy.toInt() != 0
			is String -> legacy == "1" || legacy.equals("true", ignoreCase = true)
			else -> false
		}
	}

	fun readContentWarning(metaData: android.os.Bundle): Int {
		return runCatching { metaData.getInt(METADATA_CONTENT_WARNING, 0) }.getOrDefault(0)
	}

	fun readCustomName(metaData: android.os.Bundle): String? {
		return metaData.getString("tachiyomix.name")?.takeIf { it.isNotBlank() }
	}

	fun resolveEntryClassName(packageName: String, className: String): String {
		return if (className.startsWith('.')) {
			packageName + className
		} else {
			className
		}
	}

	fun getSignatures(pkgInfo: PackageInfo): List<String>? {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			val signingInfo = pkgInfo.signingInfo ?: return null
			if (signingInfo.hasMultipleSigners()) {
				signingInfo.apkContentsSigners
			} else {
				signingInfo.signingCertificateHistory
			}
		} else {
			@Suppress("DEPRECATION")
			pkgInfo.signatures
		}
			?.map { it.sha256Fingerprint() }
			?.toList()
	}

	fun selectPreferred(
		shared: MihonInstalledExtensionPackage?,
		private: MihonInstalledExtensionPackage?,
	): MihonInstalledExtensionPackage? {
		return when {
			shared == null -> private
			private == null -> shared
			PackageInfoCompat.getLongVersionCode(shared.packageInfo) >=
				PackageInfoCompat.getLongVersionCode(private.packageInfo) -> shared
			else -> private
		}
	}

	private fun ApplicationInfo.fixBasePaths(apkPath: String) {
		if (sourceDir == null) {
			sourceDir = apkPath
		}
		if (publicSourceDir == null) {
			publicSourceDir = apkPath
		}
	}

	private fun Signature.sha256Fingerprint(): String {
		return MessageDigest.getInstance("SHA-256")
			.digest(toByteArray())
			.joinToString(separator = "") { byte ->
				"%02x".format(Locale.US, byte)
			}
	}
}
