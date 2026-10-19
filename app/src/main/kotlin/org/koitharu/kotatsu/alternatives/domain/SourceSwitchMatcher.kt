package org.koitharu.kotatsu.alternatives.domain

import java.util.Locale

/**
 * Decides whether two source entries serve the same site: by host first, then by a normalized
 * display name in a compatible language.
 */
object SourceSwitchMatcher {

	private val bracketed = Regex("""[(\[{][^)\]}]*[)\]}]""")
	private val domainSuffix = Regex("""\.(com|net|org|io|to|me|cc|co|xyz|info|site|online|club|top|vip|fun|app|moe|tv|ws|us|uk|in|id|vn|ru|br|es|fr|de|jp|kr|cn|tw)$""")
	private val nonAlphanumeric = Regex("""[^\p{L}\p{Nd}]+""")
	private val multiLanguageCodes = setOf("", "all", "multi", "other")

	fun normalizeName(name: String): String = name.lowercase(Locale.ROOT)
		.replace(bracketed, " ")
		.trim()
		.replace(domainSuffix, "")
		.replace(nonAlphanumeric, "")

	fun normalizeHost(host: String): String? = host.trim()
		.lowercase(Locale.ROOT)
		.substringAfter("://")
		.substringBefore('/')
		.substringBefore(':')
		.removePrefix("www.")
		.removePrefix("m.")
		.takeIf { it.contains('.') }

	fun hostsMatch(a: String, b: String): Boolean {
		val x = normalizeHost(a) ?: return false
		val y = normalizeHost(b) ?: return false
		return x == y || x.endsWith(".$y") || y.endsWith(".$x")
	}

	fun anyHostMatches(hosts: Collection<String>, candidate: String?): Boolean =
		candidate != null && hosts.any { hostsMatch(it, candidate) }

	fun namesMatch(a: String, b: String): Boolean {
		val x = normalizeName(a)
		return x.length >= MIN_NAME_LENGTH && x == normalizeName(b)
	}

	fun languagesCompatible(a: String?, b: String?): Boolean {
		val x = a?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_').orEmpty()
		val y = b?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_').orEmpty()
		return x in multiLanguageCodes || y in multiLanguageCodes || x == y
	}

	private const val MIN_NAME_LENGTH = 3
}
