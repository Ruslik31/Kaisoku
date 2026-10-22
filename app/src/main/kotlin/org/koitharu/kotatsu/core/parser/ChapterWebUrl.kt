package org.koitharu.kotatsu.core.parser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Chapter identifiers can be opaque IDs, local URIs, or web paths. Never infer an ID's route. */
internal fun chapterWebUrl(raw: String?): String? {
    if (raw.isNullOrEmpty() || raw.length > 8192 || raw != raw.trim() ||
        raw.any { Character.isISOControl(it) || it == '\\' } ||
        !(raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true))
    ) return null
    val url = raw.toHttpUrlOrNull() ?: return null
    val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if ('@' in authority || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.toString()
}

internal fun resolveChapterWebUrl(domain: String, path: String, mangaDex: Boolean = false): String? {
    chapterWebUrl(path)?.let { return it }
    if (path.isEmpty() || path != path.trim() || path.any { Character.isISOControl(it) || it == '\\' } ||
        SCHEME.containsMatchIn(path)
    ) return null
    val base = chapterWebUrl(if ("://" in domain) domain else "https://$domain/")?.toHttpUrlOrNull()
        ?: return null
    if (mangaDex && CHAPTER_UUID.matches(path)) {
        return base.newBuilder().encodedPath("/chapter/$path").query(null).fragment(null).build().toString()
    }
    val webPath = path.substringBefore('?').substringBefore('#')
    // A single opaque value (e.g. an API ID) is not a declared website path.
    if (webPath.all { it == '/' } || ('/' !in webPath && !CHAPTER_FILE.matches(webPath))) return null
    return chapterWebUrl(base.resolve(path)?.toString())
}

internal fun lnReaderChapterWebPath(chapterUrl: String): String = chapterUrl.substringAfter("|||", chapterUrl)

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val CHAPTER_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val CHAPTER_FILE = Regex("[^/?#]+\\.[A-Za-z][A-Za-z0-9]{0,7}")
