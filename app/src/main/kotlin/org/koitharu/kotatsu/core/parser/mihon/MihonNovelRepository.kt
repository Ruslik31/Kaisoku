package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.ChapterTextRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class MihonNovelRepository(
    private val loaded: MihonExtensionManager.LoadedSource,
    cache: MemoryContentCache,
    appSettings: AppSettings,
) : MihonMangaRepository(loaded, cache, appSettings), ChapterTextRepository {
    private val novel = requireNotNull(loaded.sourceInstance as? NovelSource)
    private val requests = MultiMutex<String>()
    private val chapters = LinkedHashMap<String, String>(8, 0.75f, true)
    private var cachedCharacters = 0

    override suspend fun getChapterHtml(chapter: MangaChapter): String = requests.withLock(chapter.url) {
        synchronized(chapters) { chapters[chapter.url] }?.let { return@withLock it }
        val html = withContext(Dispatchers.IO) {
            // These wrappers deliberately do not implement their image-oriented page parsers.
            val site = (loaded.sourceInstance as? HttpSource)?.baseUrl.orEmpty()
            val base = site.toHttpUrlOrNull()?.resolve(chapter.url)?.toString() ?: site
            fetchMihonChapterHtml(novel, chapter.url, base)
        }
        synchronized(chapters) {
            while (chapters.isNotEmpty() && (chapters.size >= 8 || cachedCharacters + html.length > MAX_CACHE_CHARS)) {
                val oldest = chapters.entries.iterator()
                cachedCharacters -= oldest.next().value.length
                oldest.remove()
            }
            if (html.length <= MAX_CACHE_CHARS) {
                chapters.put(chapter.url, html)?.let { cachedCharacters -= it.length }
                cachedCharacters += html.length
            }
        }
        html
    }

    override suspend fun getImageHeaders(): Map<String, String> = withContext(Dispatchers.IO) {
        (loaded.sourceInstance as? HttpSource)?.headers?.toMap().orEmpty()
    }

    override fun createPageRequest(pageUrl: String, page: MangaPage): Request {
        val http = loaded.sourceInstance as? HttpSource ?: return super.createPageRequest(pageUrl, page)
        return http.imageRequest(Page(index = 0, url = pageUrl, imageUrl = pageUrl)).newBuilder()
            .tag(MangaSource::class.java, page.source).build()
    }

    companion object {
        private const val MAX_CACHE_CHARS = 4 * 1024 * 1024
    }
}

internal suspend fun fetchMihonChapterHtml(source: NovelSource, chapterUrl: String, baseUrl: String): String =
    normalizeMihonChapterHtml(source.fetchPageText(Page(index = 0, url = chapterUrl)), baseUrl)

internal fun normalizeMihonChapterHtml(raw: String, baseUrl: String): String {
    if (raw.isBlank()) throw IOException("Novel source returned no chapter text")
    if (raw.length > 8 * 1024 * 1024) throw IOException("Novel chapter text is too large")
    val doc = Jsoup.parseBodyFragment(raw, baseUrl)
    doc.outputSettings().prettyPrint(false)
    doc.select("img[src]").forEach { image ->
        image.absUrl("src").takeIf(String::isNotBlank)?.let { image.attr("src", it) }
    }
    return doc.body().html().takeIf(String::isNotBlank)
        ?: throw IOException("Novel source returned no chapter text")
}
