package org.koitharu.kotatsu.suggestions.local

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaTagsEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity

data class RecommendationTasteRow(
    @Embedded val manga: MangaEntity,
    @Relation(parentColumn = "manga_id", entityColumn = "tag_id", associateBy = Junction(MangaTagsEntity::class))
    val tags: List<TagEntity>,
    val favorite: Boolean,
    val lastRead: Long,
    val durationMillis: Long,
    val progress: Float,
    val historyChapters: Int,
)
