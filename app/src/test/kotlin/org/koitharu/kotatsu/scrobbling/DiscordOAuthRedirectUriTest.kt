package org.koitharu.kotatsu.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.discord.data.DISCORD_OAUTH_REDIRECT_URI

class DiscordOAuthRedirectUriTest {

	@Test
	fun `discord oauth redirect uses registered kaisoku scheme`() {
		assertTrue(DISCORD_OAUTH_REDIRECT_URI.startsWith("kaisoku://"))
	}

	@Test
	fun `discord oauth redirect path matches manifest host`() {
		assertEquals("kaisoku://discord-auth", DISCORD_OAUTH_REDIRECT_URI)
	}
}
