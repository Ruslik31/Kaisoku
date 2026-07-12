package org.koitharu.kotatsu.scrobbling.discord.ui

import okio.utf8Size

private const val MANGA_APP_LINK = "https://kotatsu.app/manga"
private const val MANGA_DEEP_LINK_SUFFIX = "://manga"

internal data class DiscordRpcButtons(
	val labels: List<String>,
	val urls: List<String>,
)

internal fun buildDiscordRpcButtons(
	appUrl: String,
	publicUrl: String,
	openInApp: String,
	openOnSite: String,
	buttonTextLimit: Int,
): DiscordRpcButtons? {
	val labels = listOf(openInApp, openOnSite)
	if (labels.any { it.utf8Size() > buttonTextLimit }) {
		return null
	}
	return DiscordRpcButtons(
		labels = labels,
		urls = listOf(appUrl.toDiscordButtonUrl(), publicUrl),
	)
}

private fun String.toDiscordButtonUrl(): String {
	val suffixStart = indexOf(MANGA_DEEP_LINK_SUFFIX, ignoreCase = true)
	if (suffixStart < 0) {
		return this
	}
	val scheme = substring(0, suffixStart)
	if (!scheme.equals("kaisoku", ignoreCase = true) && !scheme.equals("kotatsu", ignoreCase = true)) {
		return this
	}
	return MANGA_APP_LINK + substring(suffixStart + MANGA_DEEP_LINK_SUFFIX.length)
}
