package org.koitharu.kotatsu.core.parser

import kotlinx.coroutines.Dispatchers
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.parser.tsuki.TsukiModelMapper
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import tsuki.MangaParser as TsukiMangaParser
import tsuki.config.ConfigKey as TsukiConfigKey
import tsuki.model.Manga as TsukiManga

/**
 * A parser plugin jar. Legacy org.koitharu.* plugins are called reflectively; tsuki.* plugins are
 * called directly through the host Tsuki core and converted with [TsukiModelMapper].
 */
class PluginMangaRepository(
	private val loadedParser: DynamicParserManager.LoadedParser,
	private val settings: SourceSettings,
	cache: MemoryContentCache,
	private val appSettings: AppSettings,
	private val mirrorSwitcher: MirrorSwitcher,
) : CachingMangaRepository(cache), Interceptor, MirrorableRepository {

	private val delegate: Any
		get() = loadedParser.delegate

	private val tsuki: TsukiMangaParser? = loadedParser.delegate as? TsukiMangaParser

	private val mapper: TsukiModelMapper? = tsuki?.let { TsukiModelMapper(loadedParser.source, it.source) }

	private val filterOptionsLazy = suspendLazy(Dispatchers.Default) {
		withMirrors {
			tsukiCall { t, m -> m.toHost(t.getFilterOptions()) }
				?: callSuspend<MangaListFilterOptions>("getFilterOptions")
		}
	}

	override val source: PluginMangaSource
		get() = loadedParser.source

	override val sortOrders: Set<SortOrder>
		get() = tsuki?.availableSortOrders?.mapNotNullTo(LinkedHashSet(), TsukiModelMapper::sortOrderToHost)
			?.ifEmpty { null }
			?: call("getAvailableSortOrders") ?: setOf(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = tsuki?.filterCapabilities?.let(TsukiModelMapper::capabilitiesToHost)
			?: call("getFilterCapabilities") ?: MangaListFilterCapabilities()

	override var defaultSortOrder: SortOrder
		get() = settings.defaultSortOrder?.takeIf { it in sortOrders }
			?: appSettings.defaultBrowseSortOrder(sortOrders, sortOrders.first())
		set(value) {
			settings.defaultSortOrder = value
		}

	override var domain: String
		get() = tsuki?.domain ?: call<String>("getDomain").orEmpty()
		set(value) {
			configKeyDomain?.let { settings[it] = value }
		}

	override val domains: Array<out String>
		get() = configKeyDomain?.presetValues ?: emptyArray()

	val configKeyDomain: ConfigKey.Domain?
		get() = tsuki?.configKeyDomain?.let { ConfigKey.Domain(*it.presetValues) } ?: call("getConfigKeyDomain")

	override fun intercept(chain: Interceptor.Chain): Response {
		tsuki?.let { return it.intercept(chain) }
		return call("intercept", chain) ?: chain.proceed(chain.request())
	}

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
		withMirrors { getListImpl(offset, order, filter) }

	private suspend fun getListImpl(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		tsukiCall { t, m ->
			val tsukiOrder = (order ?: defaultSortOrder).let(TsukiModelMapper::sortOrderToTsuki)
				?.takeIf { it in t.availableSortOrders }
				?: t.availableSortOrders.first()
			t.getList(offset, tsukiOrder, m.toTsuki(filter ?: MangaListFilter.EMPTY)).map(m::toHost)
		}?.let { return it }
		return callSuspend(
			"getList",
			offset,
			order ?: defaultSortOrder,
			filter ?: MangaListFilter.EMPTY,
		)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withMirrors {
		tsukiCall { t, m -> t.getPages(m.toTsuki(chapter)).map(m::toHost) }
			?: callSuspend("getPages", chapter)
	}

    override suspend fun getChapterUrl(manga: Manga, chapter: MangaChapter): String? =
        resolveChapterWebUrl(domain, chapter.url, mangaDex = source.sourceName == "MANGADEX")

	override suspend fun getPageUrl(page: MangaPage): String =
		(tsukiCall { t, m -> t.getPageUrl(m.toTsuki(page)) } ?: callSuspend<String>("getPageUrl", page))
			.also { result ->
				check(result.isNotEmpty()) { "Page url is empty" }
			}

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptionsLazy.get()

	suspend fun getFavicons(): Favicons =
		tsukiCall { t, m -> m.toHost(t.getFavicons()) } ?: callSuspend("getFavicons")

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> =
		tsukiCall { t, m -> t.getRelatedManga(m.toTsuki(seed)).map(m::toHost) }
			?: callSuspend("getRelatedManga", seed)

	override suspend fun discover(order: SortOrder, filter: MangaListFilter): List<Manga> =
		getList(0, order, filter)

	override val supportsGenreDiscovery: Boolean get() = true

	override val supportsRelatedDiscovery: Boolean
		get() = if (tsuki != null) {
			tsuki.javaClass.getMethod(
				"getRelatedManga", TsukiManga::class.java, kotlin.coroutines.Continuation::class.java,
			).declaringClass.name != "tsuki.core.AbstractMangaParser"
		} else {
			delegate.javaClass.getMethod(
				"getRelatedManga", Manga::class.java, kotlin.coroutines.Continuation::class.java,
			).declaringClass.name != "org.koitharu.kotatsu.parsers.core.AbstractMangaParser"
		}

	override suspend fun discoverRelated(seed: Manga): List<Manga> =
		if (supportsRelatedDiscovery) getRelatedMangaImpl(seed) else emptyList()

	override suspend fun getDetailsImpl(manga: Manga): Manga = withMirrors {
		tsukiCall { t, m -> m.toHost(t.getDetails(m.toTsuki(manga))) }
			?: callSuspend("getDetails", manga)
	}

	fun getAuthProvider(): MangaParserAuthProvider? {
		val tsukiAuth = tsuki?.authorizationProvider ?: return delegate as? MangaParserAuthProvider
		val m = checkNotNull(mapper)
		return object : MangaParserAuthProvider {
			override val authUrl: String
				get() = tsukiAuth.authUrl

			override suspend fun isAuthorized(): Boolean = m.bridge { tsukiAuth.isAuthorized() }

			override suspend fun getUsername(): String = m.bridge { tsukiAuth.getUsername().orEmpty() }
		}
	}

	fun getRequestHeaders(): Headers = tsuki?.getRequestHeaders()
		?: call("getRequestHeaders") ?: Headers.Builder().build()

	fun getConfigKeys(): List<ConfigKey<*>> = if (tsuki != null) {
		ArrayList<TsukiConfigKey<*>>().also { tsuki.onCreateConfig(it) }
			.mapNotNull(TsukiModelMapper::configKeyToHost)
	} else {
		ArrayList<ConfigKey<*>>().also {
			DynamicParserManager.invoke(delegate, "onCreateConfig", it)
		}
	}

	fun getConfig(): SourceSettings = settings

	private suspend fun <T : Any> withMirrors(block: suspend () -> T): T = mirrorSwitcher.withMirrors(this, block)

	/** Runs [block] against the tsuki.* parser, or returns null for a legacy plugin. */
	private inline fun <T> tsukiCall(block: (TsukiMangaParser, TsukiModelMapper) -> T): T? {
		val t = tsuki ?: return null
		val m = checkNotNull(mapper)
		return m.bridge { block(t, m) }
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> call(name: String, vararg args: Any?): T? =
		DynamicParserManager.invoke(delegate, name, *args) as? T

	@Suppress("UNCHECKED_CAST")
	private suspend fun <T> callSuspend(name: String, vararg args: Any?): T =
		DynamicParserManager.invokeSuspend(delegate, name, *args) as T
}
