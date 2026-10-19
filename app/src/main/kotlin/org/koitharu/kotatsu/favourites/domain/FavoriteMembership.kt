package org.koitharu.kotatsu.favourites.domain

import org.koitharu.kotatsu.favourites.data.FavouriteEntity

/** An add is idempotent, including for metadata formerly hidden behind a deletion. */
internal fun favoriteMembership(
    existing: FavouriteEntity?,
    mangaId: Long,
    categoryId: Long,
    createdAt: Long,
): FavouriteEntity = existing?.let { if (it.deletedAt == 0L) it else it.copy(deletedAt = 0L) }
    ?: FavouriteEntity(mangaId, categoryId, sortKey = 0, isPinned = false, createdAt = createdAt, deletedAt = 0L)
