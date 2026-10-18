package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.core.parser.tsuki.TsukiModelMapper
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import tsuki.model.MangaSource as TsukiMangaSource

/**
 * A source served by a parser plugin jar. [delegate] is the plugin's own enum constant: a host
 * [MangaSource] for legacy org.koitharu.* plugins, or a [TsukiMangaSource] for tsuki.* plugins.
 */
data class PluginMangaSource(
	val delegate: Any,
	val jarName: String,
) : MangaSource {

	override val name: String
		get() = "$jarName:$sourceName"

	val sourceName: String
		get() = when (delegate) {
			is TsukiMangaSource -> delegate.name
			is MangaSource -> delegate.name
			else -> delegate.toString()
		}

	val isTsuki: Boolean
		get() = delegate is TsukiMangaSource

	val locale: String
		get() = (delegate as? TsukiMangaSource)?.locale ?: delegate.callString("getLocale").orEmpty()

	val contentType: ContentType
		get() = (delegate as? TsukiMangaSource)?.let { TsukiModelMapper.contentTypeToHost(it.contentType) }
			?: delegate.call("getContentType")
			?: ContentType.MANGA

	val title: String
		get() = ((delegate as? TsukiMangaSource)?.title ?: delegate.callString("getTitle"))
			?.takeIf { it.isNotBlank() } ?: sourceName

	val isBroken: Boolean
		get() = (delegate as? TsukiMangaSource)?.isBroken
			?: delegate.call("isBroken") ?: delegate.call("getIsBroken") ?: false
}

data class UnresolvedMangaSource(
	override val name: String,
) : MangaSource

private inline fun <reified T> Any.call(methodName: String): T? = runCatching {
	javaClass.getMethod(methodName).invoke(this) as? T
}.getOrNull()

private fun Any.callString(methodName: String): String? = call(methodName)
