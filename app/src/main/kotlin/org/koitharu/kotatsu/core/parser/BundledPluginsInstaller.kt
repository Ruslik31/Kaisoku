package org.koitharu.kotatsu.core.parser

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.settings.sources.manage.plugins.UpdatePluginsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the parser plugins shipped in the APK once. Each copy keeps its GitHub origin so the
 * regular plugin updater replaces it with newer releases. A plugin the user deletes stays deleted.
 */
@Singleton
class BundledPluginsInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
	private val updatePluginsProvider: UpdatePluginsProvider,
) {

	private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

	@WorkerThread
	fun install() {
		val pluginsDir = PluginFileLoader.pluginsDir(context)
		for (plugin in BUNDLED) {
			val seededKey = KEY_SEEDED_PREFIX + plugin.fileName
			if (prefs.getBoolean(seededKey, false)) {
				continue
			}
			runCatching {
				val target = File(pluginsDir, plugin.fileName)
				// An existing copy may be newer or user-supplied: never replace it or its origin, and
				// skip when the same repository is already installed under another file name.
				val isInstalledElsewhere = updatePluginsProvider.readDto().values.any {
					it.repository.equals(plugin.repository, ignoreCase = true)
				}
				if (!target.exists() && !isInstalledElsewhere) {
					context.assets.open(ASSETS_DIR + plugin.fileName).use { input ->
						PluginFileLoader.copyFromStream(target, input)
					}
					updatePluginsProvider.saveDto(plugin.fileName, plugin.repository, plugin.tag)
				}
				prefs.edit { putBoolean(seededKey, true) }
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	data class BundledPlugin(
		val fileName: String,
		val repository: String,
		val tag: String,
	)

	companion object {

		val BUNDLED = listOf(
			BundledPlugin(fileName = "uma.jar", repository = "InvalidDavid/UMA", tag = "f207e8e"),
		)

		private const val PREFS_NAME = "bundled_plugins"
		private const val KEY_SEEDED_PREFIX = "seeded_v1_"
		private const val ASSETS_DIR = "plugins/"
	}
}
