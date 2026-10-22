package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.UnresolvedLnReaderSource
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource

/** Text is a repository capability; readers need not know the optional runtime that supplies it. */
interface ChapterTextRepository {
    suspend fun getChapterHtml(chapter: MangaChapter): String

    suspend fun getImageHeaders(): Map<String, String> = emptyMap()
}

fun MangaSource.hasChapterText(): Boolean = when (val source = unwrap()) {
    is LnReaderMangaSource, is UnresolvedLnReaderSource -> true
    is MihonMangaSource -> source.resolved().let { it.isCatalogue && it.isNovelSource }
    else -> false
}
