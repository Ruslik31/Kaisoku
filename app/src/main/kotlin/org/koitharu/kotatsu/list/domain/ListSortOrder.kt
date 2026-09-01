package org.koitharu.kotatsu.list.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.util.find
import java.util.EnumSet

enum class ListSortOrder(
	@StringRes val titleResId: Int,
) {

	NEWEST(R.string.order_added),
	OLDEST(R.string.order_oldest),
	PROGRESS(R.string.progress),
	UNREAD(R.string.unread),
	UNREAD_CHAPTERS(R.string.by_unread_chapters),
	UNREAD_CHAPTERS_REVERSE(R.string.by_unread_chapters_reverse),
	ALPHABETIC(R.string.by_name),
	ALPHABETIC_REVERSE(R.string.by_name_reverse),
	RATING(R.string.by_rating),
	RATING_REVERSE(R.string.by_rating),
	RELEVANCE(R.string.by_relevance),
	NEW_CHAPTERS(R.string.new_chapters),
	NEW_CHAPTERS_REVERSE(R.string.new_chapters),
	LAST_READ(R.string.last_read),
	LONG_AGO_READ(R.string.long_ago_read),
	UPDATED(R.string.updated),
	UPDATED_REVERSE(R.string.updated),
	TOTAL_CHAPTERS(R.string.total_chapters),
	TOTAL_CHAPTERS_REVERSE(R.string.total_chapters),
	;

	val type: Type
		get() = when (this) {
			ALPHABETIC, ALPHABETIC_REVERSE -> Type.ALPHABETICAL
			NEWEST, OLDEST -> Type.DATE_ADDED
			PROGRESS, UNREAD -> Type.PROGRESS
			UNREAD_CHAPTERS, UNREAD_CHAPTERS_REVERSE -> Type.UNREAD_CHAPTERS
			RATING, RATING_REVERSE -> Type.RATING
			NEW_CHAPTERS, NEW_CHAPTERS_REVERSE -> Type.NEW_CHAPTERS
			LAST_READ, LONG_AGO_READ -> Type.LAST_READ
			UPDATED, UPDATED_REVERSE -> Type.UPDATED
			TOTAL_CHAPTERS, TOTAL_CHAPTERS_REVERSE -> Type.TOTAL_CHAPTERS
			RELEVANCE -> Type.RELEVANCE
		}

	val isAscending: Boolean
		get() = this in ASCENDING

	fun isGroupingSupported() = type == Type.LAST_READ || type == Type.DATE_ADDED || type == Type.PROGRESS

	enum class Type(
		@StringRes val titleResId: Int,
	) {
		ALPHABETICAL(R.string.by_name),
		DATE_ADDED(R.string.order_added),
		PROGRESS(R.string.progress),
		UNREAD_CHAPTERS(R.string.by_unread_chapters),
		RATING(R.string.by_rating),
		NEW_CHAPTERS(R.string.new_chapters),
		LAST_READ(R.string.last_read),
		UPDATED(R.string.updated),
		TOTAL_CHAPTERS(R.string.total_chapters),
		RELEVANCE(R.string.by_relevance),
	}

	companion object {

		val HISTORY: Set<ListSortOrder> = EnumSet.of(
			LAST_READ,
			LONG_AGO_READ,
			NEWEST,
			OLDEST,
			PROGRESS,
			UNREAD,
			UNREAD_CHAPTERS,
			UNREAD_CHAPTERS_REVERSE,
			ALPHABETIC,
			ALPHABETIC_REVERSE,
			NEW_CHAPTERS,
			NEW_CHAPTERS_REVERSE,
			UPDATED,
			UPDATED_REVERSE,
			TOTAL_CHAPTERS,
			TOTAL_CHAPTERS_REVERSE,
		)
		val FAVORITES: Set<ListSortOrder> = EnumSet.of(
			ALPHABETIC,
			ALPHABETIC_REVERSE,
			NEWEST,
			OLDEST,
			RATING,
			RATING_REVERSE,
			NEW_CHAPTERS,
			NEW_CHAPTERS_REVERSE,
			PROGRESS,
			UNREAD,
			UNREAD_CHAPTERS,
			UNREAD_CHAPTERS_REVERSE,
			LAST_READ,
			LONG_AGO_READ,
			UPDATED,
			UPDATED_REVERSE,
			TOTAL_CHAPTERS,
			TOTAL_CHAPTERS_REVERSE,
		)
		val SUGGESTIONS: Set<ListSortOrder> = EnumSet.of(RELEVANCE)

		val FAVORITE_TYPES: List<Type> = Type.entries - Type.RELEVANCE
		val HISTORY_TYPES: List<Type> = Type.entries.filterNot { it == Type.RELEVANCE || it == Type.RATING }

		private val ASCENDING = EnumSet.of(
			ALPHABETIC,
			OLDEST,
			UNREAD,
			UNREAD_CHAPTERS_REVERSE,
			RATING_REVERSE,
			NEW_CHAPTERS_REVERSE,
			LONG_AGO_READ,
			UPDATED_REVERSE,
			TOTAL_CHAPTERS_REVERSE,
		)

		fun from(type: Type, isAscending: Boolean): ListSortOrder = when (type) {
			Type.ALPHABETICAL -> if (isAscending) ALPHABETIC else ALPHABETIC_REVERSE
			Type.DATE_ADDED -> if (isAscending) OLDEST else NEWEST
			Type.PROGRESS -> if (isAscending) UNREAD else PROGRESS
			Type.UNREAD_CHAPTERS -> if (isAscending) UNREAD_CHAPTERS_REVERSE else UNREAD_CHAPTERS
			Type.RATING -> if (isAscending) RATING_REVERSE else RATING
			Type.NEW_CHAPTERS -> if (isAscending) NEW_CHAPTERS_REVERSE else NEW_CHAPTERS
			Type.LAST_READ -> if (isAscending) LONG_AGO_READ else LAST_READ
			Type.UPDATED -> if (isAscending) UPDATED_REVERSE else UPDATED
			Type.TOTAL_CHAPTERS -> if (isAscending) TOTAL_CHAPTERS_REVERSE else TOTAL_CHAPTERS
			Type.RELEVANCE -> RELEVANCE
		}

		operator fun invoke(value: String, fallback: ListSortOrder) = entries.find(value) ?: fallback
	}
}
