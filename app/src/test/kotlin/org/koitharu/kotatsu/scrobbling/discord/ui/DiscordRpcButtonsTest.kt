package org.koitharu.kotatsu.scrobbling.discord.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordRpcButtonsTest {

	@Test
	fun `builds open in app and source buttons`() {
		val buttons = buildDiscordRpcButtons(
			appUrl = "https://kotatsu.app/manga?source=TEST_SOURCE&name=Demo%20Manga&url=%2Fmanga%2Fdemo",
			publicUrl = "https://example.org/manga/demo",
			openInApp = "Open in Kaisoku",
			openOnSite = "Open in Example",
			buttonTextLimit = 32,
		)

		assertEquals(listOf("Open in Kaisoku", "Open in Example"), buttons?.labels)
		assertEquals(
			listOf(
				"https://kotatsu.app/manga?source=TEST_SOURCE&name=Demo%20Manga&url=%2Fmanga%2Fdemo",
				"https://example.org/manga/demo",
			),
			buttons?.urls,
		)
	}

	@Test
	fun `converts custom app scheme to web app link for discord`() {
		val buttons = buildDiscordRpcButtons(
			appUrl = "kaisoku://manga?source=TEST_SOURCE&name=Demo%20Manga&url=%2Fmanga%2Fdemo",
			publicUrl = "https://example.org/manga/demo",
			openInApp = "Open in Kaisoku",
			openOnSite = "Open in Example",
			buttonTextLimit = 32,
		)

		assertEquals(
			"https://kotatsu.app/manga?source=TEST_SOURCE&name=Demo%20Manga&url=%2Fmanga%2Fdemo",
			buttons?.urls?.first(),
		)
	}

	@Test
	fun `omits buttons when a label exceeds discord limit`() {
		val buttons = buildDiscordRpcButtons(
			appUrl = "kaisoku://manga",
			publicUrl = "https://example.org/manga/demo",
			openInApp = "Open in app with a label that is too long",
			openOnSite = "Open on site",
			buttonTextLimit = 32,
		)

		assertNull(buttons)
	}
}
