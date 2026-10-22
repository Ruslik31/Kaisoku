package org.koitharu.kotatsu.settings.sources.manage.plugins

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.parser.DynamicParserManager
import org.koitharu.kotatsu.core.parser.PluginFileLoader
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PluginsManageViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val updatePluginsProvider: UpdatePluginsProvider,
	private val settings: AppSettings,
) : BaseViewModel() {

	val content = MutableStateFlow<List<PluginManageItem>>(emptyList())
	val selection = MutableStateFlow<Set<String>>(emptySet())
	private val prefs by lazy {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

	@Volatile
	private var pluginsSnapshot = emptyList<PluginManageItem.Plugin>()

	@Volatile
	private var query = ""

	init {
		refresh()
	}

	fun refresh() {
		launchLoadingJob(Dispatchers.Default) {
			val localPlugins = loadPluginsLocal()
			pluginsSnapshot = localPlugins
			publishFiltered()

			if (localPlugins.isNotEmpty()) {
				val updatedPlugins = coroutineScope {
					localPlugins.map { plugin ->
						async {
							val repo = plugin.repository ?: return@async plugin
							val latest = requestLatestTag(repo) ?: return@async plugin
							plugin.copy(latestTag = latest)
						}
					}.awaitAll()
				}
				pluginsSnapshot = updatedPlugins
				publishFiltered()
			}
		}
	}

	/** Runs the regular plugin auto-update (if enabled) and refreshes the list when it finishes. */
	fun runAutoUpdate() {
		launchJob(Dispatchers.Default) {
			updatePluginsProvider.runAutoUpdate(settings)
			refresh()
		}
	}

	fun toggleSelection(jarName: String) {
		val current = selection.value
		selection.value = if (jarName in current) current - jarName else current + jarName
		publishFiltered()
	}

	fun clearSelection() {
		if (selection.value.isEmpty()) return
		selection.value = emptySet()
		publishFiltered()
	}

	suspend fun deleteSelected(): Boolean = withContext(Dispatchers.Default) {
		val selected = selection.value
		if (selected.isEmpty()) return@withContext false
		val pluginsDir = PluginFileLoader.pluginsDir(context)
		var isSuccess = true
		for (jarName in selected) {
			runCatchingCancellable {
				File(pluginsDir, jarName).takeIf { it.exists() }?.run {
					setWritable(true, true)
					check(delete()) { "Cannot delete $jarName" }
				}
				clearGithubMeta(jarName)
			}.onFailure { isSuccess = false }
		}
		selection.value = emptySet()
		reloadPlugins(pluginsDir)
		isSuccess
	}.also { refresh() }

	/** The JAR name is part of every source identity; rename only its display label. */
	suspend fun renamePlugin(item: PluginManageItem.Plugin, rawName: String): Boolean = withContext(Dispatchers.Default) {
		val label = rawName.trim().takeIf { it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl) }
			?: return@withContext false
		if (!File(PluginFileLoader.pluginsDir(context), item.jarName).isFile) return@withContext false
		prefs.edit { putString(KEY_PLUGIN_LABEL_PREFIX + item.jarName, label) }
		true
	}.also { if (it) refresh() }

	fun setQuery(value: String?) {
		query = value?.trim().orEmpty()
		publishFiltered()
	}

	suspend fun resolveGithubRelease(input: String): ExternalPluginDto? = resolveGithubReleases(input).firstOrNull()

	/** Every jar asset of the repository's latest release. */
	suspend fun resolveGithubReleases(input: String): List<ExternalPluginDto> = withContext(Dispatchers.Default) {
		val repository = normalizeRepository(input) ?: return@withContext emptyList()
		val tag = requestLatestTag(repository) ?: return@withContext emptyList()
		requestReleaseAssets(repository, tag)
	}

	suspend fun importFromUri(uri: Uri, fileName: String): Boolean = withContext(Dispatchers.Default) {
		val safeName = sanitizeJarFileName(fileName)
		runCatchingCancellable {
			val pluginsDir = PluginFileLoader.pluginsDir(context)
			PluginFileLoader.copyFromUri(context, uri, File(pluginsDir, safeName))
			clearGithubMeta(safeName)
			reloadPlugins(pluginsDir)
		}.isSuccess
	}.also { if (it) refresh() }

	suspend fun importFromGithub(release: ExternalPluginDto, fileName: String = release.fileName): Boolean =
		withContext(Dispatchers.Default) {
			val safeName = sanitizeJarFileName(fileName)
			runCatchingCancellable {
				val pluginsDir = PluginFileLoader.pluginsDir(context)
				val outFile = File(pluginsDir, safeName)
				val request = Request.Builder()
					.get()
					.url(release.downloadUrl)
					.build()
				okHttpClient.newCall(request).await().use { response ->
					if (!response.isSuccessful) {
						throw IOException("Plugin download failed: ${response.code}")
					}
					PluginFileLoader.copyFromStream(outFile, response.body.byteStream())
				}
				saveGithubMeta(safeName, release.repository, release.tag)
				reloadPlugins(pluginsDir)
			}.isSuccess
		}.also {
			if (it) refresh()
		}

	fun importPlugin(
		uri: Uri,
		getOriginalName: (Uri) -> String?,
		askName: suspend (String) -> String?,
		askOverwrite: suspend (String) -> Boolean,
		onResult: (Boolean) -> Unit,
	) {
		launchJob(Dispatchers.Default) {
			val originalName = getOriginalName(uri) ?: "plugin_${System.currentTimeMillis()}.jar"
			val pluginName = askName(originalName.removeSuffix(".jar"))?.trim().orEmpty()
			if (pluginName.isBlank()) {
				return@launchJob
			}

			val fileName = sanitizeJarFileName(pluginName)
			if (isInstalled(fileName) && !askOverwrite(fileName)) {
				return@launchJob
			}

			val success = importFromUri(uri, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	fun importGithubPlugin(
		askInput: suspend () -> String?,
		askSelect: suspend (List<String>) -> Int?,
		askOverwrite: suspend (String) -> Boolean,
		onResult: (Boolean) -> Unit,
	) {
		launchJob(Dispatchers.Default) {
			val input = askInput()?.trim()?.takeIf { it.isNotBlank() } ?: return@launchJob
			val releases = resolveGithubReleases(input)
			if (releases.isEmpty()) {
				withContext(Dispatchers.Main) { onResult(false) }
				return@launchJob
			}
			val release = if (releases.size == 1) {
				releases.single()
			} else {
				val index = askSelect(releases.map { it.fileName }) ?: return@launchJob
				releases.getOrNull(index) ?: return@launchJob
			}

			val fileName = sanitizeJarFileName(release.fileName)
			if (isInstalled(fileName) && !askOverwrite(fileName)) {
				return@launchJob
			}

			val success = importFromGithub(release, fileName)
			withContext(Dispatchers.Main) { onResult(success) }
		}
	}

	suspend fun updatePlugin(item: PluginManageItem.Plugin): Boolean {
		val repository = item.repository ?: return false
		val release = pickAsset(resolveGithubReleases(repository), item.jarName) ?: return false
		return if (release.tag == item.installedTag) {
			refresh()
			true
		} else {
			importFromGithub(release, item.jarName)
		}
	}

	suspend fun deletePlugin(item: PluginManageItem.Plugin): Boolean = withContext(Dispatchers.Default) {
		runCatchingCancellable {
			DynamicParserManager.deletePlugin(context, item.jarName)
			clearGithubMeta(item.jarName)
			mangaSourcesRepository.refreshInstalledPluginSources()
		}.isSuccess
	}.also {
		if (it) refresh()
	}

	fun sanitizeJarFileName(rawName: String): String {
		val sanitized = rawName
			.trim()
			.replace('/', '_')
			.replace('\\', '_')
			.ifBlank { "plugin_${System.currentTimeMillis()}.jar" }
		return if (sanitized.lowercase(Locale.ROOT).endsWith(".jar")) sanitized else "$sanitized.jar"
	}

	fun isInstalled(fileName: String): Boolean {
		return File(PluginFileLoader.pluginsDir(context), sanitizeJarFileName(fileName)).exists()
	}

	private fun publishFiltered() {
		val all = pluginsSnapshot
		if (all.isEmpty()) {
			content.value = listOf(
				PluginManageItem.Placeholder(
					titleResId = R.string.no_plugins_installed,
					summaryResId = R.string.no_plugins_summary,
				),
			)
			return
		}
		val selected = selection.value
		val q = query
		if (q.isBlank()) {
			content.value = all.map { it.copy(isSelected = it.jarName in selected) }
			return
		}
		val filtered = all.map { it.copy(isSelected = it.jarName in selected) }.filter { plugin ->
			plugin.jarName.contains(q, ignoreCase = true) ||
				plugin.displayName.contains(q, ignoreCase = true) ||
				plugin.repository?.contains(q, ignoreCase = true) == true
		}
		content.value = filtered.ifEmpty {
			listOf(PluginManageItem.Placeholder(titleResId = R.string.nothing_found, summaryResId = null))
		}
	}

	private fun loadPluginsLocal(): List<PluginManageItem.Plugin> {
		val plugins = DynamicParserManager.getInstalledPlugins(context).sorted()
		if (plugins.isEmpty()) {
			return emptyList()
		}
		val meta = readAndCleanupMeta(plugins.toSet())
		val loaded = MangaSourceRegistry.entries.filterIsInstance<PluginMangaSource>().groupBy { it.jarName }
		val errors = DynamicParserManager.getLoadErrors()
		return plugins.map { fileName ->
			val itemMeta = meta[fileName]
			val sources = loaded[fileName].orEmpty()
			PluginManageItem.Plugin(
				jarName = fileName,
				customName = prefs.getString(KEY_PLUGIN_LABEL_PREFIX + fileName, null),
				repository = itemMeta?.repository,
				installedTag = itemMeta?.tag,
				latestTag = null,
				sourcesCount = sources.size,
				isTsuki = sources.any { it.isTsuki },
				loadError = errors[fileName]?.let { it.message ?: it.javaClass.simpleName },
			)
		}
	}

	private suspend fun reloadPlugins(pluginsDir: File) {
		DynamicParserManager.loadParsersFromDirectory(context, pluginsDir)
		mangaSourcesRepository.refreshInstalledPluginSources()
	}


	private suspend fun requestLatestTag(repository: String): String? {
		return runCatchingCancellable {
			val request = Request.Builder()
				.get()
				.url("https://github.com/$repository/releases/latest")
				.build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) {
					return null
				}
				val pathSegments = response.request.url.pathSegments
				val tagIndex = pathSegments.indexOf("tag")
				val tag = if (tagIndex >= 0) {
					pathSegments.getOrNull(tagIndex + 1)
				} else {
					pathSegments.lastOrNull()
				}
				tag?.takeIf { it.isNotBlank() }
			}
		}.getOrNull()
	}

	private suspend fun requestReleaseAssets(repository: String, tag: String): List<ExternalPluginDto> {
		return runCatchingCancellable {
			val (owner, repoName) = splitRepository(repository) ?: return emptyList()
			val url = HttpUrl.Builder()
				.scheme("https")
				.host("api.github.com")
				.addPathSegments("repos/$owner/$repoName/releases/tags/$tag")
				.build()
			val request = Request.Builder()
				.get()
				.url(url)
				.build()
			okHttpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) {
					return emptyList()
				}
				val body = response.body.string()
				if (body.isBlank()) {
					return emptyList()
				}
				val json = JSONObject(body)
				findJarAssets(json.optJSONArray("assets")).map { (name, url) ->
					ExternalPluginDto(repository, tag, name, url)
				}
			}
		}.getOrDefault(emptyList())
	}

	private fun normalizeRepository(input: String): String? {
		val trimmed = input.trim().takeIf { it.isNotEmpty() } ?: return null
		return (GITHUB_URL_REGEX.matchEntire(trimmed) ?: REPOSITORY_REGEX.matchEntire(trimmed))
			?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
	}

	private fun splitRepository(repository: String): Pair<String, String>? {
		val parts = repository.split('/', limit = 2)
		if (parts.size < 2) {
			return null
		}
		val owner = parts[0].trim()
		val repo = parts[1].trim()
		if (owner.isBlank() || repo.isBlank()) {
			return null
		}
		return owner to repo
	}

	private fun findJarAssets(assets: JSONArray?): List<Pair<String, String>> =
		UpdatePluginsProvider.findJarAssets(assets)

	private fun pickAsset(assets: List<ExternalPluginDto>, jarName: String): ExternalPluginDto? =
		assets.firstOrNull { it.fileName.equals(jarName, ignoreCase = true) } ?: assets.singleOrNull()

	private fun readAndCleanupMeta(installedFiles: Set<String>): MutableMap<String, GithubMeta> {
		val meta = readMeta()
		if (meta.keys.retainAll(installedFiles)) {
			writeMeta(meta)
		}
		return meta
	}

	private fun saveGithubMeta(fileName: String, repository: String, tag: String) {
		updateMeta {
			it[fileName] = GithubMeta(repository = repository, tag = tag)
		}
	}

	private fun clearGithubMeta(fileName: String) {
		updateMeta {
			it.remove(fileName)
		}
	}

	private fun updateMeta(block: (MutableMap<String, GithubMeta>) -> Unit) {
		val meta = readMeta()
		block(meta)
		writeMeta(meta)
	}

	private fun readMeta(): MutableMap<String, GithubMeta> {
		val raw = prefs.getString(PREFS_KEY_GITHUB_META, null).orEmpty()
		if (raw.isBlank()) {
			return LinkedHashMap()
		}
		return runCatching {
			val json = JSONObject(raw)
			val out = LinkedHashMap<String, GithubMeta>(json.length())
			val keys = json.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				val obj = json.optJSONObject(key) ?: continue
				val repository = obj.optString(JSON_KEY_REPOSITORY)
				val tag = obj.optString(JSON_KEY_TAG)
				if (repository.isNotBlank() && tag.isNotBlank()) {
					out[key] = GithubMeta(repository, tag)
				}
			}
			out
		}.getOrElse { LinkedHashMap() }
	}

	private fun writeMeta(meta: Map<String, GithubMeta>) {
		val json = JSONObject()
		meta.forEach { (fileName, value) ->
			json.put(
				fileName,
				JSONObject()
					.put(JSON_KEY_REPOSITORY, value.repository)
					.put(JSON_KEY_TAG, value.tag),
			)
		}
		prefs.edit {
			putString(PREFS_KEY_GITHUB_META, json.toString())
		}
	}

	private data class GithubMeta(
		val repository: String,
		val tag: String,
	)

	private companion object {
		const val KEY_PLUGIN_LABEL_PREFIX = "display_name:"
		const val PREFS_NAME = "plugins_manage"
		const val PREFS_KEY_GITHUB_META = "github_meta"
		const val JSON_KEY_REPOSITORY = "repository"
		const val JSON_KEY_TAG = "tag"
		val REPOSITORY_REGEX = Regex("""^\s*([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?\s*$""")
		val GITHUB_URL_REGEX = Regex(
			"""(?i)^\s*(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?\s*$""",
		)
	}
}
