package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.RecommendationWarning

/** Only an optional warning icon accompanies the normal cards or empty placeholder. */
fun recommendationContent(cards: List<ListModel>, error: String?): List<ListModel> = buildList {
    RecommendationWarning.from(error)?.let(::add)
    addAll(cards)
}
