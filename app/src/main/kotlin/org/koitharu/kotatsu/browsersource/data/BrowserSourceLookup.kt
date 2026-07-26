package org.koitharu.kotatsu.browsersource.data

import org.koitharu.kotatsu.customsource.domain.CustomSource
import org.koitharu.kotatsu.customsource.domain.CustomSourceType
import java.net.URI

/**
 * Reduces user input to the scheme/host/port a browser source is keyed by, so `theblank.net/search`
 * and `https://theblank.net/` resolve to the same site. Returns `null` when no host can be read.
 */
fun normaliseBrowserSourceUrl(input: String): String? {
	val trimmed = input.trim()
	if (trimmed.isEmpty()) {
		return null
	}
	val withScheme = when {
		trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
		else -> "https://$trimmed"
	}
	return runCatching {
		val uri = URI(withScheme)
		val host = uri.host ?: return null
		"${uri.scheme}://$host${if (uri.port != -1) ":${uri.port}" else ""}"
	}.getOrNull()
}

/**
 * Finds the existing browser source for [normalisedUrl], if any.
 *
 * Only [CustomSourceType.BROWSER_SOURCE] entries count: a site registered under another custom-source
 * type (a parser-backed or manual-WebView entry) describes a different way of reading the same site
 * and must not stand in for — or block — a browser source for it.
 */
fun Iterable<CustomSource>.findBrowserSource(normalisedUrl: String): CustomSource? {
	val key = normalisedUrl.trimEnd('/').lowercase()
	return firstOrNull {
		it.type == CustomSourceType.BROWSER_SOURCE && it.baseUrl.trimEnd('/').lowercase() == key
	}
}
