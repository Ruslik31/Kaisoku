package org.koitharu.kotatsu.core.parser

import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy

class ParserMangaRepository(
	private val parser: MangaParser,
	private val mirrorSwitcher: MirrorSwitcher,
	cache: MemoryContentCache,
	private val appSettings: AppSettings,
) : CachingMangaRepository(cache), Interceptor, MirrorableRepository {

	private val filterOptionsLazy = suspendLazy(Dispatchers.Default) {
		withMirrors {
			parser.getFilterOptions()
		}
	}

	override val source: MangaParserSource
		get() = parser.source

	override val sortOrders: Set<SortOrder>
		get() = parser.availableSortOrders

	override val filterCapabilities: MangaListFilterCapabilities
		get() = parser.filterCapabilities

	override var defaultSortOrder: SortOrder
		get() = getConfig().defaultSortOrder
			?: appSettings.defaultBrowseSortOrder(sortOrders, sortOrders.first())
		set(value) {
			getConfig().defaultSortOrder = value
		}

	override var domain: String
		get() = parser.domain
		set(value) {
			getConfig()[parser.configKeyDomain] = value
		}

	override val domains: Array<out String>
		get() = parser.configKeyDomain.presetValues

	override fun intercept(chain: Interceptor.Chain): Response = parser.intercept(chain)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		return withMirrors {
			parser.getList(offset, order ?: defaultSortOrder, filter ?: MangaListFilter.EMPTY)
		}
	}

	override suspend fun getPagesImpl(
		chapter: MangaChapter
	): List<MangaPage> = withMirrors {
		parser.getPages(chapter)
	}

    override suspend fun getChapterUrl(manga: Manga, chapter: MangaChapter): String? =
        resolveChapterWebUrl(domain, chapter.url, mangaDex = source == MangaParserSource.MANGADEX)

	override suspend fun getPageUrl(page: MangaPage): String = withMirrors {
		parser.getPageUrl(page).also { result ->
			check(result.isNotEmpty()) { "Page url is empty" }
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptionsLazy.get()

	suspend fun getFavicons(): Favicons = withMirrors {
		parser.getFavicons()
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = parser.getRelatedManga(seed)

    override suspend fun discover(order: SortOrder, filter: MangaListFilter): List<Manga> =
        parser.getList(0, order, filter)

    override val supportsGenreDiscovery: Boolean get() = true

    override val supportsRelatedDiscovery: Boolean get() = parser.javaClass.getMethod(
        "getRelatedManga", Manga::class.java, kotlin.coroutines.Continuation::class.java,
    ).declaringClass != org.koitharu.kotatsu.parsers.core.AbstractMangaParser::class.java

    override suspend fun discoverRelated(seed: Manga): List<Manga> =
        if (supportsRelatedDiscovery) parser.getRelatedManga(seed) else emptyList()

	override suspend fun getDetailsImpl(manga: Manga): Manga = withMirrors {
		parser.getDetails(manga)
	}

	fun getAuthProvider(): MangaParserAuthProvider? = parser.authorizationProvider

	fun getRequestHeaders() = parser.getRequestHeaders()

	fun getConfigKeys(): List<ConfigKey<*>> = ArrayList<ConfigKey<*>>().also {
		parser.onCreateConfig(it)
	}

	fun getAvailableMirrors(): List<String> {
		return parser.configKeyDomain.presetValues.toList()
	}

	fun isSlowdownEnabled(): Boolean {
		return getConfig().isSlowdownEnabled
	}

	fun getConfig() = parser.config as SourceSettings

	private suspend fun <T : Any> withMirrors(block: suspend () -> T): T = mirrorSwitcher.withMirrors(this, block)
}
