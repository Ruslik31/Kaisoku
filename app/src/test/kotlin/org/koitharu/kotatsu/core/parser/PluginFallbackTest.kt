package org.koitharu.kotatsu.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class PluginFallbackTest {

	@Test
	fun installedPluginSourceCanResolveMatchingBuiltInParser() {
		assertEquals(
			MangaParserSource.YAOIX3,
			findBuiltInParserSource(MangaParserSource.YAOIX3.name),
		)
	}

	@Test
	fun unknownPluginSourceHasNoUnsafeFallback() {
		assertNull(findBuiltInParserSource("SOURCE_NOT_IN_THIS_APP_BUILD"))
	}
}
