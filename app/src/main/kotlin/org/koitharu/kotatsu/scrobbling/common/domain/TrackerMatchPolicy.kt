package org.koitharu.kotatsu.scrobbling.common.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal fun canonicalTrackerId(url: String, host: String): Long? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
    if (uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") != host) return null
    val path = Regex("^/manga/([0-9]+)(?:/.*)?$").matchEntire(uri.path) ?: return null
    path.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
}.getOrNull()

private fun matchTitle(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

/** Exact title suggestions are still untrusted; sequels, editions and same-named works must be confirmed. */
internal fun uniqueTitleSuggestion(titles: Set<String>, matches: List<ScrobblerManga>): Long? {
    val names = titles.map(::matchTitle).filter { it.length >= 3 }.toSet()
    return matches.distinctBy { it.id }.filter { matchTitle(it.name) in names || it.altName?.let(::matchTitle) in names }
        .singleOrNull()?.id
}
