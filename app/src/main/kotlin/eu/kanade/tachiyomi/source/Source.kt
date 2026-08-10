package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 * Ported from Mihon source-api for extension compatibility.
 */
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).toBlocking().first()
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).toBlocking().first()
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).toBlocking().first()
    }

    // ======== TachiyomiX 1.6 additions (additive; default-delegate to the 1.4/1.5 fetch* API) ========

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     */
    @Suppress("DEPRECATION")
    suspend fun getPopularManga(page: Int): eu.kanade.tachiyomi.source.model.MangasPage {
        throw IllegalStateException("Not used")
    }

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getLatestUpdates(page: Int): eu.kanade.tachiyomi.source.model.MangasPage {
        throw IllegalStateException("Not used")
    }

    /**
     * Get a page with a list of manga matching a query + filters.
     *
     * @since tachiyomi 1.6
     */
    suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): eu.kanade.tachiyomi.source.model.MangasPage {
        throw IllegalStateException("Not used")
    }

    /**
     * Returns the list of filters for the source. Moved up from CatalogueSource in tachiyomix 1.6.
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Combined "update" for a manga: fetches details and/or chapters.
     *
     * @since tachiyomix 1.6
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) getChapterList(manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}
