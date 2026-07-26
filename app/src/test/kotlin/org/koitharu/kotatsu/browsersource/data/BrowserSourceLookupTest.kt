package org.koitharu.kotatsu.browsersource.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.customsource.domain.CustomSource
import org.koitharu.kotatsu.customsource.domain.CustomSourceType

class BrowserSourceLookupTest {

	@Test
	fun urlNormalisationDropsPathAndAddsScheme() {
		assertEquals("https://theblank.net", normaliseBrowserSourceUrl("https://theblank.net/search"))
		assertEquals("https://theblank.net", normaliseBrowserSourceUrl("theblank.net"))
		assertEquals("https://theblank.net", normaliseBrowserSourceUrl("  https://theblank.net/  "))
		assertEquals("http://theblank.net:8080", normaliseBrowserSourceUrl("http://theblank.net:8080/x"))
		assertNull(normaliseBrowserSourceUrl(""))
	}

	@Test
	fun existingBrowserSourceIsFoundForAnyPathOfTheSameSite() {
		val existing = browserSource(id = 1L, baseUrl = "https://theblank.net")
		val sources = listOf(existing)

		val normalised = checkNotNull(normaliseBrowserSourceUrl("https://theblank.net/search"))

		assertEquals(existing, sources.findBrowserSource(normalised))
	}

	@Test
	fun trailingSlashAndCaseDoNotCreateASecondEntry() {
		val existing = browserSource(id = 1L, baseUrl = "https://TheBlank.net/")

		assertNotNull(listOf(existing).findBrowserSource("https://theblank.net"))
	}

	@Test
	fun aParserBackedEntryForTheSameSiteDoesNotCountAsABrowserSource() {
		// The reported lockout: theblank.net was already registered as a parser-backed source, so the
		// add dialog rejected it as a duplicate while that entry was broken and could not be replaced.
		val parserBacked = CustomSource(
			id = 7L,
			name = "TheBlank",
			baseUrl = "https://theblank.net",
			type = CustomSourceType.KOTATSU_PARSER,
			parserSourceName = "THEBLANK",
		)

		assertNull(listOf(parserBacked).findBrowserSource("https://theblank.net"))
	}

	@Test
	fun otherSitesAreNotMatched() {
		val other = browserSource(id = 2L, baseUrl = "https://mangadex.org")

		assertNull(listOf(other).findBrowserSource("https://theblank.net"))
	}

	private fun browserSource(id: Long, baseUrl: String) = CustomSource(
		id = id,
		name = "Theblank",
		baseUrl = baseUrl,
		type = CustomSourceType.BROWSER_SOURCE,
	)
}
