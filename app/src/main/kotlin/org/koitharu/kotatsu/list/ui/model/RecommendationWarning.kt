package org.koitharu.kotatsu.list.ui.model

/** Refresh diagnostics are kept out of list text and opened explicitly. */
data class RecommendationWarning(
    val details: String,
) : ListModel {
    override fun areItemsTheSame(other: ListModel) = other is RecommendationWarning

    companion object {
        fun from(details: String?): RecommendationWarning? =
            details?.takeIf { it.isNotBlank() }?.let { RecommendationWarning(it) }
    }
}
