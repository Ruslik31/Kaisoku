package org.koitharu.kotatsu.alternatives.domain

data class AlternativesSearchOptions(
	val query: String,
	val sameLanguageOnly: Boolean = false,
	val sameContentTypeOnly: Boolean = false,
	val hideNoChapters: Boolean = false,
	val sortOrder: AlternativeSortOrder = AlternativeSortOrder.BEST_MATCH,
)

enum class AlternativeSortOrder {
	BEST_MATCH,
	MOST_CHAPTERS,
	CLOSEST_CHAPTER_COUNT,
	SOURCE_PRIORITY,
}
