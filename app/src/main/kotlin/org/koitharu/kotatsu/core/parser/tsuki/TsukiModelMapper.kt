package org.koitharu.kotatsu.core.parser.tsuki

import org.koitharu.kotatsu.core.exceptions.InputRequiredException
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Favicon
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.io.IOException
import tsuki.config.ConfigKey as TConfigKey
import tsuki.exception.AuthRequiredException as TAuthRequiredException
import tsuki.exception.ContentUnavailableException as TContentUnavailableException
import tsuki.exception.GraphQLException as TGraphQLException
import tsuki.exception.InputRequiredException as TInputRequiredException
import tsuki.exception.NotFoundException as TNotFoundException
import tsuki.exception.ParseException as TParseException
import tsuki.exception.TooManyRequestExceptions as TTooManyRequestExceptions
import tsuki.model.ContentRating as TContentRating
import tsuki.model.ContentType as TContentType
import tsuki.model.Demographic as TDemographic
import tsuki.model.Favicons as TFavicons
import tsuki.model.Manga as TManga
import tsuki.model.MangaChapter as TMangaChapter
import tsuki.model.MangaListFilter as TMangaListFilter
import tsuki.model.MangaListFilterCapabilities as TMangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions as TMangaListFilterOptions
import tsuki.model.MangaPage as TMangaPage
import tsuki.model.MangaSource as TMangaSource
import tsuki.model.MangaState as TMangaState
import tsuki.model.MangaTag as TMangaTag
import tsuki.model.SortOrder as TSortOrder

/**
 * Converts between the host model ([source]) and the model of one tsuki.* plugin source ([tsukiSource]).
 * Both libraries share the same field layout; only the package and the source type differ.
 */
class TsukiModelMapper(
	val source: MangaSource,
	val tsukiSource: TMangaSource,
) {

	fun toHost(manga: TManga): Manga = Manga(
		id = manga.id,
		title = manga.title,
		altTitles = manga.altTitles,
		url = manga.url,
		publicUrl = manga.publicUrl,
		rating = manga.rating,
		contentRating = manga.contentRating?.toHost(),
		coverUrl = manga.coverUrl,
		tags = manga.tags.mapTo(LinkedHashSet(manga.tags.size), ::toHost),
		state = manga.state?.toHost(),
		authors = manga.authors,
		largeCoverUrl = manga.largeCoverUrl,
		description = manga.description,
		chapters = manga.chapters?.map(::toHost),
		source = source,
	)

	fun toTsuki(manga: Manga): TManga = TManga(
		id = manga.id,
		title = manga.title,
		altTitles = manga.altTitles,
		url = manga.url,
		publicUrl = manga.publicUrl,
		rating = manga.rating,
		contentRating = manga.contentRating?.toTsuki(),
		coverUrl = manga.coverUrl,
		tags = manga.tags.mapTo(LinkedHashSet(manga.tags.size), ::toTsuki),
		state = manga.state?.toTsuki(),
		authors = manga.authors,
		largeCoverUrl = manga.largeCoverUrl,
		description = manga.description,
		chapters = manga.chapters?.map(::toTsuki),
		source = tsukiSource,
	)

	fun toHost(chapter: TMangaChapter): MangaChapter = MangaChapter(
		id = chapter.id,
		title = chapter.title,
		number = chapter.number,
		volume = chapter.volume,
		url = chapter.url,
		scanlator = chapter.scanlator,
		uploadDate = chapter.uploadDate,
		branch = chapter.branch,
		source = source,
	)

	fun toTsuki(chapter: MangaChapter): TMangaChapter = TMangaChapter(
		id = chapter.id,
		title = chapter.title,
		number = chapter.number,
		volume = chapter.volume,
		url = chapter.url,
		scanlator = chapter.scanlator,
		uploadDate = chapter.uploadDate,
		branch = chapter.branch,
		source = tsukiSource,
	)

	fun toHost(page: TMangaPage): MangaPage = MangaPage(
		id = page.id,
		url = page.url,
		preview = page.preview,
		source = source,
	)

	fun toTsuki(page: MangaPage): TMangaPage = TMangaPage(
		id = page.id,
		url = page.url,
		preview = page.preview,
		source = tsukiSource,
	)

	fun toHost(tag: TMangaTag): MangaTag = MangaTag(title = tag.title, key = tag.key, source = source)

	fun toTsuki(tag: MangaTag): TMangaTag = TMangaTag(title = tag.title, key = tag.key, source = tsukiSource)

	fun toTsuki(filter: MangaListFilter): TMangaListFilter = TMangaListFilter(
		query = filter.query,
		tags = filter.tags.mapTo(LinkedHashSet(filter.tags.size), ::toTsuki),
		tagsExclude = filter.tagsExclude.mapTo(LinkedHashSet(filter.tagsExclude.size), ::toTsuki),
		locale = filter.locale,
		originalLocale = filter.originalLocale,
		states = filter.states.mapNotNullTo(LinkedHashSet()) { it.toTsuki() },
		contentRating = filter.contentRating.mapNotNullTo(LinkedHashSet()) { it.toTsuki() },
		types = filter.types.mapNotNullTo(LinkedHashSet()) { it.toTsuki() },
		demographics = filter.demographics.mapNotNullTo(LinkedHashSet()) { it.toTsuki() },
		year = filter.year,
		yearFrom = filter.yearFrom,
		yearTo = filter.yearTo,
		author = filter.author,
	)

	fun toHost(options: TMangaListFilterOptions): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = options.availableTags.mapTo(LinkedHashSet(options.availableTags.size), ::toHost),
		availableStates = options.availableStates.mapNotNullTo(LinkedHashSet()) { it.toHost() },
		availableContentRating = options.availableContentRating.mapNotNullTo(LinkedHashSet()) { it.toHost() },
		availableContentTypes = options.availableContentTypes.mapNotNullTo(LinkedHashSet()) { it.toHost() },
		availableDemographics = options.availableDemographics.mapNotNullTo(LinkedHashSet()) { it.toHost() },
		availableLocales = options.availableLocales,
	)

	fun toHost(favicons: TFavicons): Favicons = Favicons(
		favicons = favicons.map { Favicon(url = it.url, size = it.size, rel = null) },
		referer = favicons.referer,
	)

	/** Runs a plugin call, rethrowing its failures as host exceptions. */
	inline fun <T> bridge(block: () -> T): T = try {
		block()
	} catch (e: Throwable) {
		throw toHost(e)
	}

	/**
	 * Maps a plugin failure onto the host exception the rest of the app already handles.
	 * Unknown types pass through unchanged.
	 */
	fun toHost(e: Throwable): Throwable = when (e) {
		is TAuthRequiredException -> AuthRequiredException(source, e.cause)
		is TInputRequiredException -> InputRequiredException(source, e.message, inputKey(e.key), e.cause)
		is TNotFoundException -> NotFoundException(e.message.orEmpty(), e.url)
		is TParseException -> ParseException(e.shortMessage, e.url, e.cause)
		is TTooManyRequestExceptions -> TooManyRequestExceptions(e.url, e.getRetryDelay())
		is TContentUnavailableException -> ContentUnavailableException(e.message.orEmpty())
		is TGraphQLException -> IOException(e.message, e)
		else -> e
	}.also { mapped ->
		if (mapped !== e) {
			mapped.stackTrace = e.stackTrace
		}
	}

	companion object {

		fun capabilitiesToHost(value: TMangaListFilterCapabilities) = MangaListFilterCapabilities(
			isMultipleTagsSupported = value.isMultipleTagsSupported,
			isTagsExclusionSupported = value.isTagsExclusionSupported,
			isSearchSupported = value.isSearchSupported,
			isSearchWithFiltersSupported = value.isSearchWithFiltersSupported,
			isYearSupported = value.isYearSupported,
			isYearRangeSupported = value.isYearRangeSupported,
			isOriginalLocaleSupported = value.isOriginalLocaleSupported,
			isAuthorSearchSupported = value.isAuthorSearchSupported,
		)

		/** Host config key for the settings screen, or null when the host has no editor for it. */
		fun configKeyToHost(key: TConfigKey<*>): ConfigKey<*>? = when (key) {
			is TConfigKey.Domain -> ConfigKey.Domain(*key.presetValues)
			is TConfigKey.ShowSuspiciousContent -> ConfigKey.ShowSuspiciousContent(key.defaultValue)
			is TConfigKey.UserAgent -> ConfigKey.UserAgent(key.defaultValue)
			is TConfigKey.SplitByTranslations -> ConfigKey.SplitByTranslations(key.defaultValue)
			is TConfigKey.PreferredImageServer -> ConfigKey.PreferredImageServer(key.presetValues, key.defaultValue)
			is TConfigKey.UserInput -> null
		}

		fun inputKey(key: String) = "input.$key"

		fun sortOrderToHost(value: TSortOrder): SortOrder? = enumByName<SortOrder>(value.name)

		fun sortOrderToTsuki(value: SortOrder): TSortOrder? = enumByName<TSortOrder>(value.name)

		private fun TContentRating.toHost() = enumByName<ContentRating>(name)
		private fun ContentRating.toTsuki() = enumByName<TContentRating>(name)
		private fun TMangaState.toHost() = enumByName<MangaState>(name)
		private fun MangaState.toTsuki() = enumByName<TMangaState>(name)
		private fun TContentType.toHost() = enumByName<ContentType>(name)
		private fun ContentType.toTsuki() = enumByName<TContentType>(name)
		private fun TDemographic.toHost() = enumByName<Demographic>(name)
		private fun Demographic.toTsuki() = enumByName<TDemographic>(name)

		fun contentTypeToHost(value: TContentType): ContentType = value.toHost() ?: ContentType.OTHER

		private inline fun <reified E : Enum<E>> enumByName(name: String): E? =
			enumValues<E>().firstOrNull { it.name == name }
	}
}
