package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonExtensionRepoRepository
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonPrivateExtensionStore
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.IOException
import javax.inject.Inject

/**
 * Moves a title to another implementation of the same site and carries over history, favourites
 * and tracking. Returns the title as stored for the new source.
 */
class SourceSwitchUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val mihonRepoRepository: MihonExtensionRepoRepository,
	private val replacementPlanner: SourceReplacementPlannerUseCase,
	private val migrateUseCase: MigrateUseCase,
) {

	suspend operator fun invoke(manga: Manga, candidate: SourceSwitchCandidate): Manga = withContext(Dispatchers.Default) {
		val target = prepareSource(candidate)
		mangaSourcesRepository.setSourcesEnabled(listOf(target), true)
		val resolved = findSameLineage(manga, target)
			?: replacementPlanner.resolve(listOf(manga), target)[manga.id]
			?: throw NoSuchElementException(manga.title)
		migrateUseCase(manga, resolved)
	}

	private suspend fun prepareSource(candidate: SourceSwitchCandidate): MangaSource {
		val extension = candidate.extension ?: return candidate.source
		val result = mihonRepoRepository.installExtension(extension)
		if (result != MihonPrivateExtensionStore.InstallResult.Success) {
			throw IOException("Extension install failed: $result")
		}
		mangaSourcesRepository.refreshInstalledMihonSources()
		return MangaSource(candidate.source.name)
	}

	/**
	 * A built-in parser and a plugin build of it share URLs and ids, so the stored title can be
	 * reused directly when the plugin answers for it.
	 */
	private suspend fun findSameLineage(manga: Manga, target: MangaSource): Manga? {
		if (!isSameLineage(manga.source, target)) return null
		return runCatchingCancellable {
			withTimeout(LINEAGE_TIMEOUT_MS) {
				mangaRepositoryFactory.create(target).getDetails(manga.copy(source = target, chapters = null))
			}
		}.getOrNull()?.takeIf { !it.chapters.isNullOrEmpty() }
	}

	private companion object {

		const val LINEAGE_TIMEOUT_MS = 30_000L

		fun lineageName(source: MangaSource): String? = when (source) {
			is MangaParserSource -> source.name
			is PluginMangaSource -> source.sourceName
			else -> null
		}

		fun isSameLineage(a: MangaSource, b: MangaSource): Boolean {
			val x = lineageName(a) ?: return false
			return x == lineageName(b)
		}
	}
}
