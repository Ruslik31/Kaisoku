package org.koitharu.kotatsu.core.parser.tsuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.exceptions.InputRequiredException
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import tsuki.config.ConfigKey as TConfigKey
import tsuki.exception.AuthRequiredException as TAuthRequiredException
import tsuki.exception.InputRequiredException as TInputRequiredException
import tsuki.exception.NotFoundException as TNotFoundException
import tsuki.exception.ParseException as TParseException
import tsuki.model.ContentRating as TContentRating
import tsuki.model.Manga as TManga
import tsuki.model.MangaChapter as TMangaChapter
import tsuki.model.MangaSource as TMangaSource
import tsuki.model.MangaState as TMangaState
import tsuki.model.MangaTag as TMangaTag
import tsuki.model.SortOrder as TSortOrder

@OptIn(tsuki.InternalParsersApi::class)
class TsukiModelMapperTest {

	private val hostSource = object : MangaSource {
		override val name = "uma.jar:EXAMPLE"
	}
	private val tsukiSource = object : TMangaSource {
		override val name = "EXAMPLE"
	}
	private val mapper = TsukiModelMapper(hostSource, tsukiSource)

	private val tsukiManga = TManga(
		id = 42L,
		title = "Title",
		altTitles = setOf("Alt"),
		url = "/manga/1",
		publicUrl = "https://example.com/manga/1",
		rating = 0.5f,
		contentRating = TContentRating.ADULT,
		coverUrl = "https://example.com/c.jpg",
		tags = setOf(TMangaTag("Action", "action", tsukiSource)),
		state = TMangaState.ONGOING,
		authors = setOf("Author"),
		chapters = listOf(
			TMangaChapter(7L, "One", 1f, 1, "/c/1", "Team", 100L, null, tsukiSource),
		),
		source = tsukiSource,
	)

	@Test
	fun mangaRoundTripKeepsFieldsAndSwapsSources() {
		val host = mapper.toHost(tsukiManga)
		assertSame(hostSource, host.source)
		assertSame(hostSource, host.chapters!!.single().source)
		assertSame(hostSource, host.tags.single().source)
		assertEquals(42L, host.id)
		assertEquals(ContentRating.ADULT, host.contentRating)
		assertEquals(MangaState.ONGOING, host.state)
		assertEquals("/c/1", host.chapters!!.single().url)
		val back = mapper.toTsuki(host)
		assertEquals(tsukiManga, back)
	}

	@Test
	fun filterKeepsQueryAndTags() {
		val tag = mapper.toHost(TMangaTag("Action", "action", tsukiSource))
		val filter = mapper.toTsuki(MangaListFilter(query = "q", tags = setOf(tag), year = 2020))
		assertEquals("q", filter.query)
		assertEquals("action", filter.tags.single().key)
		assertSame(tsukiSource, filter.tags.single().source)
		assertEquals(2020, filter.year)
	}

	@Test
	fun sortOrdersMapByName() {
		for (order in SortOrder.entries) {
			assertEquals(order.name, TsukiModelMapper.sortOrderToTsuki(order)?.name)
		}
		assertEquals(SortOrder.POPULARITY, TsukiModelMapper.sortOrderToHost(TSortOrder.POPULARITY))
	}

	@Test
	fun configKeysMapForTheSettingsScreen() {
		val domain = TsukiModelMapper.configKeyToHost(TConfigKey.Domain("a.com", "b.com")) as ConfigKey.Domain
		assertEquals(listOf("a.com", "b.com"), domain.presetValues.toList())
		assertNull(TsukiModelMapper.configKeyToHost(TConfigKey.UserInput("password")))
	}

	@Test
	fun pluginExceptionsBecomeHostExceptions() {
		assertTrue(mapper.toHost(TAuthRequiredException(tsukiSource)) is AuthRequiredException)
		val notFound = mapper.toHost(TNotFoundException("gone", "https://example.com/x"))
		assertEquals("https://example.com/x", (notFound as NotFoundException).url)
		val parse = mapper.toHost(TParseException("bad", "https://example.com/y"))
		assertEquals("https://example.com/y", (parse as ParseException).url)
		val input = mapper.toHost(TInputRequiredException(tsukiSource, "Password", "pass")) as InputRequiredException
		assertSame(hostSource, input.source)
		assertEquals("input.pass", input.key)
		val other = IllegalStateException()
		assertSame(other, mapper.toHost(other))
	}
}
