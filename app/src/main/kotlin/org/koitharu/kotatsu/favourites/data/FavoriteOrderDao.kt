package org.koitharu.kotatsu.favourites.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FavoriteOrderDao {
    @Query("SELECT * FROM favorite_order WHERE manga_id=:mangaId AND scope_id=:scopeId")
    suspend fun find(mangaId: Long, scopeId: Long): FavoriteOrderEntity?

    @Query("SELECT * FROM favorite_order WHERE scope_id=0 OR EXISTS(SELECT 1 FROM favourite_categories c " +
        "WHERE c.category_id=favorite_order.scope_id) ORDER BY scope_id, rank, manga_id")
    suspend fun dump(): List<FavoriteOrderEntity>

    @Query("SELECT COALESCE(MAX(updated_at),0) FROM favorite_order WHERE scope_id=:scopeId")
    suspend fun lastRevision(scopeId: Long): Long

    @Upsert
    suspend fun upsert(entity: FavoriteOrderEntity)
}
