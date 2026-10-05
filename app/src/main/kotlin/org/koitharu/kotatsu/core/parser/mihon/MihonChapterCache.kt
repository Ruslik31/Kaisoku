package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.model.SChapter

/** Retains extension-specific chapter fields without conflating identical URLs in different titles. */
internal class MihonChapterCache(private val maxTitles: Int = 4) {
    private val titles = LinkedHashMap<String, Map<String, SChapter>>(maxTitles, 0.75f, true)

    @Synchronized
    fun put(mangaUrl: String, chapters: List<SChapter>) {
        titles[mangaUrl] = chapters.associateBy { it.url }
        while (titles.size > maxTitles) titles.remove(titles.keys.first())
    }

    @Synchronized
    fun get(mangaUrl: String, chapterUrl: String): SChapter? = titles[mangaUrl]?.get(chapterUrl)
}
