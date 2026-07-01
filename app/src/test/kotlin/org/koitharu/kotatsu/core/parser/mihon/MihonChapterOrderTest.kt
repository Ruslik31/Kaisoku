package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class MihonChapterOrderTest {

	private fun chapter(name: String) = SChapter.create().also {
		it.url = "/$name"
		it.name = name
	}

	/**
	 * Mihon/Tachiyomi sources return chapters newest-first, but Kotatsu expects them oldest-first
	 * (index 0 = the first/oldest chapter). Without reversing, every Mihon extension source shows
	 * its chapters reversed relative to native sources and to Mihon itself.
	 */
	@Test
	fun reversesMihonNewestFirstToKotatsuOldestFirst() {
		val mihonOrder = listOf(
			chapter("Chapter 06"),
			chapter("Chapter 05"),
			chapter("Chapter 04"),
			chapter("Chapter 03"),
			chapter("Chapter 02"),
			chapter("Chapter 01"),
		)
		val kotatsuOrder = mihonOrder.toKaisokuChapterOrder().map { it.name }
		assertEquals(
			listOf("Chapter 01", "Chapter 02", "Chapter 03", "Chapter 04", "Chapter 05", "Chapter 06"),
			kotatsuOrder,
		)
	}

	/** The oldest chapter must end up first so the index-based fallback numbering counts up from it. */
	@Test
	fun fallbackNumberingCountsFromOldest() {
		val mihonOrder = listOf(chapter("newest"), chapter("middle"), chapter("oldest"))
		val ordered = mihonOrder.toKaisokuChapterOrder()
		assertEquals("oldest", ordered.first().name)
		assertEquals(listOf(1, 2, 3), ordered.mapIndexed { index, _ -> index + 1 })
	}

	@Test
	fun emptyListStaysEmpty() {
		assertEquals(emptyList<String>(), emptyList<SChapter>().toKaisokuChapterOrder().map { it.name })
	}
}
