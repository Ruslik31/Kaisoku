package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable

/**
 * Result of [eu.kanade.tachiyomi.source.Source.getMangaUpdate].
 *
 * @since tachiyomix 1.6
 */
@Serializable
data class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
)
