package org.koitharu.kotatsu.suggestions.domain

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

class TagsBlacklist(private val tags: Set<String>) {

	fun isNotEmpty() = tags.isNotEmpty()

	operator fun contains(manga: Manga): Boolean {
		if (tags.isEmpty()) {
			return false
		}
		return manga.tags.any { it in this }
	}

	operator fun contains(tag: MangaTag): Boolean = RecommendationTagExclusions.contains(tags, tag.title)
}
