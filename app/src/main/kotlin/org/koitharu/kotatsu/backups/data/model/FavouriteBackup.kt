package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteManga

@Serializable
class FavouriteBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("sort_key") val sortKey: Int = 0,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long = 0L,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: FavouriteManga) : this(
		mangaId = entity.manga.id,
		categoryId = entity.favourite.categoryId,
		sortKey = entity.favourite.sortKey,
		isPinned = entity.favourite.isPinned,
		createdAt = entity.favourite.createdAt,
		deletedAt = entity.favourite.deletedAt,
		manga = MangaBackup(MangaWithTags(entity.manga, entity.tags)),
	)

	fun toEntity(categoryId: Long = this.categoryId) = FavouriteEntity(
		mangaId = mangaId,
		categoryId = categoryId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = deletedAt,
	)
}
