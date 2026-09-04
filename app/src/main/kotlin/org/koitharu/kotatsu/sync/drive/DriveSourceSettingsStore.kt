package org.koitharu.kotatsu.sync.drive

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveSourceSettingsStore @Inject constructor(
	@ApplicationContext private val context: Context,
	private val sourcesRepository: MangaSourcesRepository,
) {

	suspend fun dump(): Map<String, Map<String, String>> = sourcesRepository.getEnabledSources().associate { source ->
		val prefs = context.getSharedPreferences(SourceSettings.prefsName(source), Context.MODE_PRIVATE)
		source.name to prefs.all.mapNotNull { (key, value) ->
			if (key !in ALLOWED_KEYS) null else encode(value)?.let { key to it }
		}.toMap()
	}.filterValues(Map<String, String>::isNotEmpty)

	fun restore(values: Map<String, Map<String, String>>) {
		values.forEach { (sourceName, sourceValues) ->
			val source = MangaSource(sourceName)
			val prefs = context.getSharedPreferences(SourceSettings.prefsName(source), Context.MODE_PRIVATE)
			prefs.edit(commit = true) {
				sourceValues.forEach { (key, encoded) ->
					if (key !in ALLOWED_KEYS) return@forEach
					when {
						encoded.startsWith("b:") -> putBoolean(key, encoded.substring(2).toBooleanStrict())
						encoded.startsWith("i:") -> putInt(key, encoded.substring(2).toInt())
						encoded.startsWith("l:") -> putLong(key, encoded.substring(2).toLong())
						encoded.startsWith("f:") -> putFloat(key, encoded.substring(2).toFloat())
						encoded.startsWith("s:") -> putString(key, encoded.substring(2))
					}
				}
			}
		}
	}

	private fun encode(value: Any?): String? = when (value) {
		is Boolean -> "b:$value"
		is Int -> "i:$value"
		is Long -> "l:$value"
		is Float -> "f:$value"
		is String -> "s:$value"
		else -> null
	}

	companion object {
		val ALLOWED_KEYS = setOf(
			SourceSettings.KEY_DOMAIN,
			SourceSettings.KEY_NO_CAPTCHA,
			SourceSettings.KEY_NO_AUTO_CAPTCHA,
			SourceSettings.KEY_SLOWDOWN,
			SourceSettings.KEY_SORT_ORDER,
			"show_suspicious",
			"user_agent",
			"split_translations",
			"img_server",
			"intercept_cloudflare",
		)
	}
}
