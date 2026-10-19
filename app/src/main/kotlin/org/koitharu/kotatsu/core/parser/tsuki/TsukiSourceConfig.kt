package org.koitharu.kotatsu.core.parser.tsuki

import org.koitharu.kotatsu.core.prefs.SourceSettings
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig

/** Source settings for a tsuki.* plugin, stored in the same preferences as host sources. */
class TsukiSourceConfig(
	private val settings: SourceSettings,
) : MangaSourceConfig {

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T = when (key) {
		is ConfigKey.UserInput -> settings.getUserInput(key.key)
		else -> TsukiModelMapper.configKeyToHost(key)?.let { settings[it] } ?: key.defaultValue
	} as T
}
