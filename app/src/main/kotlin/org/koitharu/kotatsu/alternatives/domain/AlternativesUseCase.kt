package org.koitharu.kotatsu.alternatives.domain

import coil3.request.CachePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.identityName
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.util.Locale
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {
	private val webViewSources = mutableMapOf<String, Boolean>()

	suspend operator fun invoke(
		manga: Manga,
		throughDisabledSources: Boolean,
		query: String = manga.title,
		sameLanguageOnly: Boolean = false,
		sameContentTypeOnly: Boolean = false,
	): Flow<Manga> {
		val sources = getSources(
			manga.source,
			throughDisabledSources,
			sameLanguageOnly,
			sameContentTypeOnly,
		)
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val semaphore = Semaphore(MAX_PARALLELISM)
		return channelFlow {
			val seen = HashSet<MangaStoredEntryKey>()
			fun markSeen(key: MangaStoredEntryKey): Boolean = synchronized(seen) {
				seen.add(key)
			}
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val list = runCatchingCancellable {
						semaphore.withPermit {
							searchHelper(query, SearchKind.TITLE)?.manga
						}
					}.getOrNull()
					list?.forEach { m ->
						val rawKey = m.storedEntryKey()
						if (rawKey == manga.storedEntryKey()) {
							return@forEach
						}
						if (!markSeen(rawKey)) {
							return@forEach
						}
						launch {
							val details = runCatchingCancellable {
								mangaRepositoryFactory.create(m.source).getAlternativeDetails(m, manga)
							}.getOrDefault(m)
							val detailsKey = details.storedEntryKey()
							if (detailsKey != rawKey && !markSeen(detailsKey)) {
								return@launch
							}
							send(details)
						}
					}
				}
			}
		}
	}

	suspend fun getCandidateSources(
		ref: MangaSource,
		sourceScope: AlternativeSourceScope,
		sameLanguageOnly: Boolean = true,
		sameContentTypeOnly: Boolean = false,
	): List<MangaSource> {
		val enabled = sourcesRepository.getEnabledSources()
		val sources = when (sourceScope) {
			AlternativeSourceScope.ENABLED -> enabled
			AlternativeSourceScope.ALL -> enabled + sourcesRepository.getDisabledSources()
		}
		return sources.asSequence()
			.map(MangaSource::unwrap)
			.distinctBy { it.name }
			.filter { it.name != ref.unwrap().name }
			.filter { source ->
				!sameLanguageOnly || source !is MangaParserSource || ref !is MangaParserSource ||
					source.locale == ref.locale
			}
			.filter { source ->
				!sameContentTypeOnly || source !is MangaParserSource || ref !is MangaParserSource ||
					source.contentType == ref.contentType
			}
			.sortedByDescending { it.priority(ref) }
			.toList()
	}

	suspend fun getSourceScopeOptions(
		ref: MangaSource,
		sameLanguageOnly: Boolean = false,
	): AlternativeSourceScopeOptions = AlternativeSourceScopeOptions(
		defaultScope = AlternativeSourceScope.ENABLED,
		presetTitle = null,
	)

	suspend fun searchSource(
		manga: Manga,
		source: MangaSource,
		query: String = manga.title,
		loadDetails: Boolean = true,
		onFailure: (() -> Unit)? = null,
	): Flow<Manga> {
		if (source.unwrap().name == manga.source.unwrap().name || query.isBlank()) {
			return emptyFlow()
		}
		return channelFlow {
			val searchHelper = searchHelperFactory.create(source)
			val list = runCatchingCancellable {
				searchHelper(query, SearchKind.TITLE)?.manga
			}.onFailure { onFailure?.invoke() }.getOrNull().orEmpty()
			for (candidate in list) {
				if (candidate.id == manga.id) continue
				val result = if (loadDetails) {
					runCatchingCancellable {
						mangaRepositoryFactory.create(candidate.source).getAlternativeDetails(candidate, manga)
					}.getOrDefault(candidate)
				} else {
					candidate
				}
				send(result)
			}
		}
	}

	fun isWebViewSource(source: MangaSource): Boolean = synchronized(webViewSources) {
		webViewSources.getOrPut(source.name) {
			val repository = mangaRepositoryFactory.create(source)
			((repository as? ParserMangaRepository)
				?.getConfigKeys()
				?.filterIsInstance<ConfigKey.DisableUpdateChecking>()
				?.any { it.defaultValue }
				== true)
		}
	}

	private fun Manga.storedEntryKey(): MangaStoredEntryKey {
		val sourceName = source.unwrap().name
		val value = if (id != 0L) {
			"id:$id"
		} else {
			"url:$url\n$publicUrl"
		}
		return MangaStoredEntryKey(sourceName, value)
	}

	private suspend fun getSources(
		ref: MangaSource,
		disabled: Boolean,
		sameLanguageOnly: Boolean,
		sameContentTypeOnly: Boolean,
	): List<MangaSource> = buildList {
		val refSource = ref.unwrap()
		if (!disabled) {
			add(refSource)
		}
		val sources = if (disabled) {
			sourcesRepository.getDisabledSources()
		} else {
			sourcesRepository.getEnabledSources()
		}
		for (source in sources) {
			val unwrapped = source.unwrap()
			add(unwrapped)
		}
	}.distinctBy { it.unwrap().name }
		.filter { source ->
			!sameLanguageOnly || source !is MangaParserSource || ref !is MangaParserSource ||
				source.locale == ref.locale
		}
		.filter { source ->
			!sameContentTypeOnly || source !is MangaParserSource || ref !is MangaParserSource ||
				source.contentType == ref.contentType
		}
		.sortedByDescending { it.priority(ref) }

	private suspend fun MangaRepository.getAlternativeDetails(candidate: Manga, ref: Manga): Manga {
		val sameSource = candidate.source.unwrap().name == ref.source.unwrap().name
		return if (sameSource && this is CachingMangaRepository) {
			getDetails(candidate, CachePolicy.WRITE_ONLY)
		} else {
			getDetails(candidate)
		}
	}

	private fun MangaSource.priority(ref: MangaSource): Int {
		var res = 0
		val source = unwrap()
		val refSource = ref.unwrap()
		if (source.identityName() == refSource.identityName()) {
			res += 8
		}
		if (source is MangaParserSource && refSource is MangaParserSource) {
			if (source.locale == refSource.locale) {
				res += 4
			} else if (source.locale.toLocale() == Locale.getDefault()) {
				res += 2
			}
			if (source.contentType == refSource.contentType) {
				res++
			}
		}
		return res
	}

	private data class MangaStoredEntryKey(
		val sourceName: String,
		val value: String,
	)
}
