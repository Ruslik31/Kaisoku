package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity

@Serializable
data class FavoriteOrderBackup(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("category_id") val scopeId: Long,
    val rank: Int,
    @SerialName("updated_at") val updatedAt: Long,
    val revision: String,
    val manga: MangaBackup,
) {
    fun toEntity(scopeId: Long = this.scopeId): FavoriteOrderEntity {
        require(scopeId >= 0 && rank >= 0 && updatedAt >= 0 && revision.isNotBlank() && revision.length <= 128)
        require(manga.id == mangaId) { "Manual order references another title" }
        return FavoriteOrderEntity(mangaId, scopeId, rank, updatedAt, revision)
    }
}
