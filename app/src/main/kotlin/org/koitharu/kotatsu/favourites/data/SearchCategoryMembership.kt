package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class SearchCategoryMembership(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @Embedded val category: FavouriteCategoryEntity,
)
