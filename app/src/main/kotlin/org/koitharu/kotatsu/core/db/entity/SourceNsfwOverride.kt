package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo

/**
 * Projection of the manual NSFW override of a single source.
 */
class SourceNsfwOverride(
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "nsfw_override") val nsfwOverride: Int,
)
