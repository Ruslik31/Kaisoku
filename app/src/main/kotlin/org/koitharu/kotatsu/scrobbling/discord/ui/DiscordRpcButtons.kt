package org.koitharu.kotatsu.scrobbling.discord.ui

import okio.utf8Size

internal data class DiscordRpcButtons(
	val labels: List<String>,
	val urls: List<String>,
)

internal fun buildDiscordRpcButtons(
	communityUrl: String,
	communityLabel: String,
	buttonTextLimit: Int,
): DiscordRpcButtons? {
	if (communityLabel.utf8Size() > buttonTextLimit) {
		return null
	}
	return DiscordRpcButtons(
		labels = listOf(communityLabel),
		urls = listOf(communityUrl),
	)
}
