package org.koitharu.kotatsu.stats.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.reader.ui.ReaderState

class ReadingStatsSessionTest {

	@Test fun stationaryReadingIsIncludedWhenTheSessionPauses() {
		val state = ReaderState(10L, 0, 0)
		val session = ReadingStatsSession.start(5L, state, 1_000L).advance(state, 61_000L)
		assertEquals(60_000L, session.stats.duration)
		assertEquals(0, session.stats.pages)
	}

	@Test fun repeatedScrollCallbacksDoNotCountTheSamePageAgain() {
		val start = ReaderState(10L, 0, 0)
		val nextPage = start.copy(page = 1)
		val session = ReadingStatsSession.start(5L, start, 1_000L)
			.advance(nextPage, 2_000L)
			.advance(nextPage.copy(scroll = 5000), 3_000L)
		assertEquals(1, session.stats.pages)
		assertEquals(2_000L, session.stats.duration)
		assertEquals(1, session.state.page)
	}

	@Test fun returningToTheInitialPageAndChangingChapterAreBothCounted() {
		val start = ReaderState(10L, 0, 0)
		val session = ReadingStatsSession.start(5L, start, 1_000L)
			.advance(start.copy(page = 1), 2_000L)
			.advance(start, 3_000L)
			.advance(start.copy(chapterId = 11L), 4_000L)
		assertEquals(3, session.stats.pages)
		assertEquals(11L, session.state.chapterId)
	}

	@Test fun resumedReadingStartsASeparateSessionAndExcludesTheBackgroundGap() {
		val state = ReaderState(10L, 4, 0)
		val before = ReadingStatsSession.start(5L, state, 1_000L).advance(state, 11_000L)
		val after = ReadingStatsSession.start(5L, state, 101_000L).advance(state, 111_000L)
		assertEquals(20_000L, before.stats.duration + after.stats.duration)
		assertEquals(101_000L, after.stats.startedAt)
		assertEquals(0, after.stats.pages)
	}
}
