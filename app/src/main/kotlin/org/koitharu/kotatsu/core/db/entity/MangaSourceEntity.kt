package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_SOURCES

@Entity(
	tableName = TABLE_SOURCES,
)
data class MangaSourceEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "source")
	val source: String,
	@ColumnInfo(name = "enabled") val isEnabled: Boolean,
	@ColumnInfo(name = "sort_key", index = true) val sortKey: Int,
	@ColumnInfo(name = "added_in") val addedIn: Int,
	@ColumnInfo(name = "used_at") val lastUsedAt: Long,
	@ColumnInfo(name = "pinned") val isPinned: Boolean,
	@ColumnInfo(name = "cf_state") val cfState: Int,
	/**
	 * Manual NSFW override: `null` inherits the source's intrinsic rating,
	 * `0` forces SFW, `1` forces NSFW.
	 */
	@ColumnInfo(name = "nsfw_override") val nsfwOverride: Int? = null,
)
