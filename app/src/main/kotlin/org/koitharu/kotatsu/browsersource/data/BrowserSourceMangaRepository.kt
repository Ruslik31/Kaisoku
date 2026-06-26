package org.koitharu.kotatsu.browsersource.data

import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Repository for a BROWSER_SOURCE custom source. The in-app [ui.BrowserSourceActivity] stashes the
 * page list it scraped from the live site in [BrowserSourcePageStore] before opening the reader, and
 * this repository hands those pages back. A manually-browsed site has no catalogue, so list / related /
 * filter degrade to empty instead of throwing (the base [EmptyMangaRepository] behaviour).
 */
class BrowserSourceMangaRepository(source: MangaSource) : EmptyMangaRepository(source) {

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = emptyList()

	override suspend fun getDetails(manga: Manga): Manga = manga

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> =
		BrowserSourcePageStore.getAndClear(chapter.id)

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()
}
