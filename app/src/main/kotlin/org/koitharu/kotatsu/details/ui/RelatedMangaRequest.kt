package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.Manga

internal data class RelatedMangaRequest(val manga: Manga, val hideAdult: Boolean) {
    // Reader progress, chapter downloads and unrelated metadata must not repeat source requests.
    val key = listOf(manga.id, manga.source.name, manga.url, manga.title, manga.tags, hideAdult)
}

internal fun relatedMangaRequest(
    details: MangaDetails?,
    initialLoadFinished: Boolean,
    enabled: Boolean,
    hideAdult: Boolean,
): RelatedMangaRequest? {
    if (!enabled || details == null || (!details.isLoaded && !initialLoadFinished)) return null
    // A failed/offline details load can still use known tags instead of suppressing recommendations forever.
    return RelatedMangaRequest(details.toManga(), hideAdult)
}
