package org.koitharu.kotatsu.core.parser

import androidx.room.withTransaction
import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.File
import javax.inject.Inject

@Reusable
class SavedMangaMetadata @Inject constructor(private val database: MangaDatabase) {

    suspend fun getDetails(seed: Manga): Manga {
        val (stored, path) = database.withTransaction {
            val entity = database.getMangaDao().find(seed.id)?.takeIf { it.manga.source == seed.source.name }
            val chapters = entity?.let { database.getChaptersDao().findAll(it.manga.id).takeIf { it.isNotEmpty() } }
            entity?.toManga(chapters) to database.getLocalMangaIndexDao().findPath(seed.id)
        }
        // Read only an already indexed title. LocalMangaIndex.get()/findSavedManga() may crawl storage.
        val embedded = if (path != null && (seed.description.isNullOrBlank() || (stored?.chapters == null && seed.chapters == null))) {
            runCatchingCancellable {
                File(path).takeIf { it.exists() }?.let { LocalMangaParser(it).getMangaInfo() }
            }.onFailure { it.printStackTraceDebug() }.getOrNull()
        } else {
            null
        }
        return mergeSavedMetadata(seed, stored, embedded)
    }
}

/** Fill absent metadata while retaining the request's title/source/URL and canonical chapter identities. */
internal fun mergeSavedMetadata(seed: Manga, stored: Manga?, embedded: Manga?): Manga {
    val saved = stored?.takeIf { it.id == seed.id && it.source.name == seed.source.name } ?: seed
    val archive = embedded?.takeIf {
        it.source.name == seed.source.name && (it.id == seed.id ||
            (it.url.isNotBlank() && it.url == seed.url) || (it.publicUrl.isNotBlank() && it.publicUrl == seed.publicUrl))
    }
    return seed.copy(
        title = saved.title.takeIf { it.isNotBlank() } ?: seed.title.takeIf { it.isNotBlank() } ?: archive?.title.orEmpty(),
        altTitles = saved.altTitles.ifEmpty { seed.altTitles }.ifEmpty { archive?.altTitles.orEmpty() },
        rating = saved.rating.takeIf { it >= 0f } ?: seed.rating.takeIf { it >= 0f } ?: archive?.rating ?: seed.rating,
        contentRating = saved.contentRating ?: seed.contentRating ?: archive?.contentRating,
        coverUrl = saved.coverUrl?.takeIf { it.isNotBlank() } ?: seed.coverUrl?.takeIf { it.isNotBlank() }
            ?: archive?.coverUrl?.takeIf { it.isNotBlank() },
        largeCoverUrl = saved.largeCoverUrl?.takeIf { it.isNotBlank() } ?: seed.largeCoverUrl?.takeIf { it.isNotBlank() }
            ?: archive?.largeCoverUrl?.takeIf { it.isNotBlank() },
        description = saved.description?.takeIf { it.isNotBlank() } ?: seed.description?.takeIf { it.isNotBlank() }
            ?: archive?.description?.takeIf { it.isNotBlank() },
        tags = saved.tags.ifEmpty { seed.tags }.ifEmpty { archive?.tags.orEmpty() },
        state = saved.state ?: seed.state ?: archive?.state,
        authors = saved.authors.ifEmpty { seed.authors }.ifEmpty { archive?.authors.orEmpty() },
        // The database has no empty/unknown marker: its absent rows are passed as null above.
        chapters = saved.chapters ?: seed.chapters ?: archive?.chapters,
    )
}
