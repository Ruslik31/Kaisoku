package org.koitharu.kotatsu.scrobbling

import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.anilist.data.REDIRECT_URI as anilistRedirectUri
import org.koitharu.kotatsu.scrobbling.mal.data.REDIRECT_URI as malRedirectUri
import org.koitharu.kotatsu.scrobbling.shikimori.data.REDIRECT_URI as shikimoriRedirectUri

class ScrobblerOAuthRedirectUriTest {

	@Test
	fun `anilist oauth redirect uses registered kotatsu scheme`() {
		assertTrue(anilistRedirectUri.startsWith("kotatsu://"))
	}

	@Test
	fun `mal oauth redirect uses registered kotatsu scheme`() {
		assertTrue(malRedirectUri.startsWith("kotatsu://"))
	}

	@Test
	fun `shikimori oauth redirect uses registered kotatsu scheme`() {
		assertTrue(shikimoriRedirectUri.startsWith("kotatsu://"))
	}
}
