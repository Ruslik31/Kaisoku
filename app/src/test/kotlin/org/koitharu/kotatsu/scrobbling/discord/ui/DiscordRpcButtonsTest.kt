package org.koitharu.kotatsu.scrobbling.discord.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordRpcButtonsTest {

	@Test
	fun `builds discord community button`() {
		val buttons = buildDiscordRpcButtons(
			communityUrl = "https://discord.gg/example",
			communityLabel = "Discord server",
			buttonTextLimit = 32,
		)

		assertEquals(listOf("Discord server"), buttons?.labels)
		assertEquals(listOf("https://discord.gg/example"), buttons?.urls)
	}

	@Test
	fun `omits buttons when a label exceeds discord limit`() {
		val buttons = buildDiscordRpcButtons(
			communityUrl = "https://discord.gg/example",
			communityLabel = "A Discord server label that is too long",
			buttonTextLimit = 32,
		)

		assertNull(buttons)
	}
}
