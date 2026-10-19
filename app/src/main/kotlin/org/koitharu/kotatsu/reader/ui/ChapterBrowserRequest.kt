package org.koitharu.kotatsu.reader.ui

import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.parser.chapterWebUrl
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

internal data class ChapterBrowserKey(
    val mangaId: Long,
    val mangaSource: String,
    val chapterId: Long,
    val chapterSource: String,
    val chapterUrl: String,
)

internal data class ChapterBrowserTarget(val manga: Manga, val chapter: MangaChapter) {
    val key: ChapterBrowserKey
        get() = ChapterBrowserKey(manga.id, manga.source.name, chapter.id, chapter.source.name, chapter.url)
}

internal class ChapterBrowserRequest(private val key: ChapterBrowserKey) {
    suspend fun resolve(timeoutMillis: Long = 10_000, resolver: suspend () -> String?): String? =
        withTimeout(timeoutMillis) { chapterWebUrl(resolver()) }

    fun canOpen(current: ChapterBrowserKey?, resumed: Boolean, focused: Boolean): Boolean =
        resumed && focused && current == key
}
