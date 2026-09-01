package org.koitharu.kotatsu.core.model

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class NsfwSourceOverridesTest {

	@After
	fun tearDown() {
		NsfwSourceOverrides.replaceAll(emptyMap())
	}

	@Test
	fun `sfw source with no override stays sfw`() {
		assertFalse(MangaParserSource.MANGA_OVH.intrinsicIsNsfw())
		assertFalse(MangaParserSource.MANGA_OVH.isNsfw())
	}

	@Test
	fun `hentai source with no override stays nsfw`() {
		assertTrue(MangaParserSource.EXHENTAI.intrinsicIsNsfw())
		assertTrue(MangaParserSource.EXHENTAI.isNsfw())
	}

	@Test
	fun `manual override marks an otherwise sfw source as nsfw`() {
		NsfwSourceOverrides.replaceAll(mapOf(MangaParserSource.MANGA_OVH.name to true))
		assertTrue(MangaParserSource.MANGA_OVH.isNsfw())
		assertFalse(MangaParserSource.MANGA_OVH.intrinsicIsNsfw())
		assertTrue(MangaParserSource.MANGA_OVH.hasNsfwOverride())
	}

	@Test
	fun `manual override clears an otherwise nsfw source`() {
		NsfwSourceOverrides.replaceAll(mapOf(MangaParserSource.EXHENTAI.name to false))
		assertFalse(MangaParserSource.EXHENTAI.isNsfw())
		assertTrue(MangaParserSource.EXHENTAI.intrinsicIsNsfw())
		assertTrue(MangaParserSource.EXHENTAI.hasNsfwOverride())
	}

	@Test
	fun `no override means isNsfw falls through to intrinsic value`() {
		assertFalse(MangaParserSource.MANGA_OVH.hasNsfwOverride())
		assertFalse(MangaParserSource.EXHENTAI.hasNsfwOverride())
	}

	@Test
	fun `replaceAll is keyed by unwrapped source name`() {
		val info = MangaSourceInfo(MangaParserSource.MANGA_OVH, isEnabled = true, isPinned = false)
		NsfwSourceOverrides.replaceAll(mapOf(MangaParserSource.MANGA_OVH.name to true))
		assertTrue(info.isNsfw())
	}
}
