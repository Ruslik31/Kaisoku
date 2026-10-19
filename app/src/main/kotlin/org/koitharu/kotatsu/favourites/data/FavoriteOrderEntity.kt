package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

/** Scope zero is All favorites, independent of every real category. Membership is never changed here. */
@Entity(
    tableName = "favorite_order",
    primaryKeys = ["manga_id", "scope_id"],
    foreignKeys = [ForeignKey(
        entity = MangaEntity::class,
        parentColumns = ["manga_id"],
        childColumns = ["manga_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class FavoriteOrderEntity(
    @ColumnInfo(name = "manga_id", index = true) val mangaId: Long,
    @ColumnInfo(name = "scope_id", index = true) val scopeId: Long,
    @ColumnInfo(name = "rank") val rank: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "revision") val revision: String,
) {
    fun isNewerThan(other: FavoriteOrderEntity): Boolean =
        compareValuesBy(this, other, FavoriteOrderEntity::updatedAt, FavoriteOrderEntity::revision,
            FavoriteOrderEntity::rank) > 0
}
