package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSwitchMatcherTest {

	@Test
	fun namesIgnoreCaseSymbolsDomainsAndQualifiers() {
		assertTrue(SourceSwitchMatcher.namesMatch("MangaDex", "mangadex"))
		assertTrue(SourceSwitchMatcher.namesMatch("Manga-Dex", "manga dex"))
		assertTrue(SourceSwitchMatcher.namesMatch("Bato.to", "Bato"))
		assertTrue(SourceSwitchMatcher.namesMatch("Asura Scans (Unoriginal)", "Asura Scans"))
		assertFalse(SourceSwitchMatcher.namesMatch("MangaDex", "MangaFire"))
		assertFalse(SourceSwitchMatcher.namesMatch("a", "a"))
	}

	@Test
	fun hostsMatchSubdomainsAndUrls() {
		assertTrue(SourceSwitchMatcher.hostsMatch("mangadex.org", "https://www.mangadex.org/"))
		assertTrue(SourceSwitchMatcher.hostsMatch("api.mangadex.org", "mangadex.org"))
		assertTrue(SourceSwitchMatcher.hostsMatch("m.site.com", "site.com:443"))
		assertFalse(SourceSwitchMatcher.hostsMatch("mangadex.org", "mangadex.com"))
		assertFalse(SourceSwitchMatcher.hostsMatch("", "site.com"))
		assertFalse(SourceSwitchMatcher.anyHostMatches(listOf("a.com"), null))
	}

	@Test
	fun languagesAllowMultilingualEntries() {
		assertTrue(SourceSwitchMatcher.languagesCompatible("en", "en"))
		assertTrue(SourceSwitchMatcher.languagesCompatible("pt-BR", "pt"))
		assertTrue(SourceSwitchMatcher.languagesCompatible("all", "ru"))
		assertTrue(SourceSwitchMatcher.languagesCompatible(null, "ru"))
		assertFalse(SourceSwitchMatcher.languagesCompatible("en", "ru"))
	}

	@Test
	fun normalizedHostDropsSchemePathAndPrefixes() {
		assertEquals("site.com", SourceSwitchMatcher.normalizeHost("https://www.site.com/path"))
		assertEquals(null, SourceSwitchMatcher.normalizeHost("localhost"))
	}
}
