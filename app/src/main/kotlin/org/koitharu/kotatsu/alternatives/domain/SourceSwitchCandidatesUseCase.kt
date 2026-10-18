package org.koitharu.kotatsu.alternatives.domain

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.parser.PluginMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonSourceRegistry
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonExtensionRepoRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Finds other implementations of the site behind [MangaSource]: a parser plugin, the built-in
 * parser, an installed Mihon extension, or one available in a configured extension repository.
 */
class SourceSwitchCandidatesUseCase @Inject constructor(
	@ApplicationContext private val context: Context,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mihonExtensionManager: MihonExtensionManager,
	private val mihonRepoRepository: MihonExtensionRepoRepository,
	private val settings: AppSettings,
) {

	suspend operator fun invoke(current: MangaSource): List<SourceSwitchCandidate> = withContext(Dispatchers.Default) {
		val source = current.unwrap()
		val identity = describe(source)
		val result = ArrayList<SourceSwitchCandidate>()
		result += findPlugins(source, identity)
		result += findBuiltIn(source, identity)
		result += findInstalledExtensions(source, identity)
		result += withTimeoutOrNull(REPO_TIMEOUT_MS) { findAvailableExtensions(identity) }.orEmpty()
		val skipNsfw = settings.isNsfwContentDisabled
		result
			.filterNot { it.source.name == source.name }
			.filterNot { skipNsfw && it.source.isNsfw() && !source.isNsfw() }
			.distinctBy { it.source.name }
			.sortedWith(compareBy<SourceSwitchCandidate>({ it.needsInstall }, { it.kind }, { !it.matchedByHost }))
	}

	private class Identity(
		val shortName: String?,
		val title: String,
		val language: String?,
		val hosts: Set<String>,
	)

	private fun describe(source: MangaSource): Identity {
		val repository = runCatchingCancellable { mangaRepositoryFactory.create(source) }.getOrNull()
		val hosts = LinkedHashSet<String>()
		runCatchingCancellable {
			when (repository) {
				is ParserMangaRepository -> {
					hosts += repository.domain
					hosts += repository.domains
				}

				is PluginMangaRepository -> {
					hosts += repository.domain
					hosts += repository.domains
				}

				else -> Unit
			}
		}
		if (source is MihonMangaSource) {
			MihonSourceRegistry.getDefaultReferer(source)?.let { referer ->
				Uri.parse(referer).host?.let(hosts::add)
			}
		}
		return Identity(
			shortName = when (source) {
				is MangaParserSource -> source.name
				is PluginMangaSource -> source.sourceName
				else -> null
			},
			title = source.getTitle(context),
			language = source.getLocale()?.language,
			hosts = hosts.filterTo(LinkedHashSet()) { it.isNotBlank() },
		)
	}

	private fun findPlugins(current: MangaSource, identity: Identity): List<SourceSwitchCandidate> =
		MangaSourceRegistry.entries.filterIsInstance<PluginMangaSource>().mapNotNull { plugin ->
			if (plugin.name == current.name || plugin.isBroken) return@mapNotNull null
			val byName = plugin.sourceName == identity.shortName ||
				(SourceSwitchMatcher.namesMatch(plugin.title, identity.title) &&
					SourceSwitchMatcher.languagesCompatible(plugin.locale, identity.language))
			if (!byName) return@mapNotNull null
			SourceSwitchCandidate(
				source = plugin,
				kind = SourceSwitchCandidate.Kind.PLUGIN,
				title = plugin.title,
				origin = plugin.jarName,
				matchedByHost = pluginHostMatches(plugin, identity),
			)
		}

	private fun pluginHostMatches(plugin: PluginMangaSource, identity: Identity): Boolean {
		if (identity.hosts.isEmpty()) return false
		val repository = runCatchingCancellable { mangaRepositoryFactory.create(plugin) }.getOrNull()
			as? PluginMangaRepository ?: return false
		return runCatchingCancellable { SourceSwitchMatcher.anyHostMatches(identity.hosts, repository.domain) }
			.getOrDefault(false)
	}

	private fun findBuiltIn(current: MangaSource, identity: Identity): List<SourceSwitchCandidate> {
		if (current is MangaParserSource) return emptyList()
		return MangaParserSource.entries.mapNotNull { builtIn ->
			if (builtIn.isBroken) return@mapNotNull null
			val byName = builtIn.name == identity.shortName ||
				(SourceSwitchMatcher.namesMatch(builtIn.title, identity.title) &&
					SourceSwitchMatcher.languagesCompatible(builtIn.locale, identity.language))
			if (!byName) return@mapNotNull null
			SourceSwitchCandidate(
				source = builtIn,
				kind = SourceSwitchCandidate.Kind.BUILT_IN,
				title = builtIn.title,
				origin = null,
				matchedByHost = false,
			)
		}
	}

	private fun findInstalledExtensions(current: MangaSource, identity: Identity): List<SourceSwitchCandidate> {
		val installed = runCatchingCancellable { mihonExtensionManager.getInstalledSources() }.getOrNull().orEmpty()
		return installed.mapNotNull { extensionSource ->
			if (extensionSource.name == current.name) return@mapNotNull null
			val resolved = extensionSource.resolved()
			val referer = MihonSourceRegistry.getDefaultReferer(resolved)
			val byHost = SourceSwitchMatcher.anyHostMatches(identity.hosts, referer)
			val title = resolved.resolveName(context)
			val byName = SourceSwitchMatcher.namesMatch(title, identity.title) &&
				SourceSwitchMatcher.languagesCompatible(resolved.locale, identity.language)
			if (!byHost && !byName) return@mapNotNull null
			SourceSwitchCandidate(
				source = resolved,
				kind = SourceSwitchCandidate.Kind.EXTENSION,
				title = title,
				origin = resolved.packageName,
				matchedByHost = byHost,
			)
		}
	}

	private suspend fun findAvailableExtensions(identity: Identity): List<SourceSwitchCandidate> = coroutineScope {
		mihonRepoRepository.getRepos().map { repo ->
			async {
				runCatchingCancellable { mihonRepoRepository.getExtensions(repo.baseUrl) }.getOrNull().orEmpty()
			}
		}.awaitAll().flatten().filterNot { it.isInstalledPrivately || it.isInstalledExternally }.flatMap { descriptor ->
			val extension = descriptor.extension
			extension.sources.mapNotNull { entry ->
				val byHost = SourceSwitchMatcher.anyHostMatches(identity.hosts, entry.baseUrl)
				val byName = SourceSwitchMatcher.namesMatch(entry.name, identity.title) &&
					SourceSwitchMatcher.languagesCompatible(entry.lang, identity.language)
				if (!byHost && !byName) return@mapNotNull null
				SourceSwitchCandidate(
					source = MihonMangaSource(
						packageName = extension.pkgName,
						sourceId = entry.id,
						displayName = entry.name,
						locale = entry.lang,
						isNsfwSource = extension.isNsfw,
					),
					kind = SourceSwitchCandidate.Kind.EXTENSION,
					title = entry.name,
					origin = extension.repo.shortName ?: extension.repo.name,
					matchedByHost = byHost,
					extension = extension,
				)
			}
		}
	}

	private companion object {

		const val REPO_TIMEOUT_MS = 10_000L
	}
}
