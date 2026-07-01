package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class MihonChapterOrderTest {

	private fun chapter(name: String, number: Float = -1f) = SChapter.create().also {
		it.url = "/$name"
		it.name = name
		it.chapter_number = number
	}

	/**
	 * Mihon/Tachiyomi sources return chapters newest-first, but Kotatsu expects them oldest-first
	 * (index 0 = the first/oldest chapter). Without reversing, every Mihon extension source shows
	 * its chapters reversed relative to native sources and to Mihon itself.
	 */
	@Test
	fun reversesMihonNewestFirstToKotatsuOldestFirst() {
		val mihonOrder = listOf(
			chapter("Chapter 06", 6f),
			chapter("Chapter 05", 5f),
			chapter("Chapter 04", 4f),
			chapter("Chapter 03", 3f),
			chapter("Chapter 02", 2f),
			chapter("Chapter 01", 1f),
		)
		val ordered = mihonOrder.toKaisokuChapterOrder().map { it.chapter.name }
		assertEquals(
			listOf("Chapter 01", "Chapter 02", "Chapter 03", "Chapter 04", "Chapter 05", "Chapter 06"),
			ordered,
		)
	}

	/** Unnumbered chapters fall back to a 1-based number counting up from the oldest chapter. */
	@Test
	fun fallbackNumberingCountsFromOldest() {
		val mihonOrder = listOf(chapter("newest"), chapter("middle"), chapter("oldest"))
		val ordered = mihonOrder.toKaisokuChapterOrder()
		assertEquals("oldest", ordered.first().chapter.name)
		assertEquals(listOf(1f, 2f, 3f), ordered.map { it.number })
	}

	/**
	 * The final sort is by chapter number (like Mihon), so even a source that returns chapters in an
	 * arbitrary order — not the usual strict newest-first — still ends up in reading order.
	 */
	@Test
	fun scrambledSourceOrderIsNormalisedByChapterNumber() {
		val scrambled = listOf(
			chapter("Chapter 02", 2f),
			chapter("Chapter 10.5", 10.5f),
			chapter("Chapter 01", 1f),
			chapter("Chapter 11", 11f),
			chapter("Chapter 10", 10f),
		)
		val ordered = scrambled.toKaisokuChapterOrder()
		assertEquals(
			listOf("Chapter 01", "Chapter 02", "Chapter 10", "Chapter 10.5", "Chapter 11"),
			ordered.map { it.chapter.name },
		)
		assertEquals(listOf(1f, 2f, 10f, 10.5f, 11f), ordered.map { it.number })
	}

	@Test
	fun emptyListStaysEmpty() {
		assertEquals(emptyList<String>(), emptyList<SChapter>().toKaisokuChapterOrder().map { it.chapter.name })
	}
}
