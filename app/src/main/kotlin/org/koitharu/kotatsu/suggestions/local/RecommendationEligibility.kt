package org.koitharu.kotatsu.suggestions.local

internal data class RecommendationReadingState(
    val key: String,
    val lastRead: Long,
    val progress: Float,
    val chapters: Int,
)

internal object RecommendationEligibility {
    fun readingAllowed(
        item: Recommendation,
        states: List<RecommendationReadingState>,
        baselines: Map<String, ChapterBaseline?>,
        threshold: Int,
        allowStarted: Boolean,
    ): Boolean {
        val started = states.filter { it.lastRead > 0 }
        if (started.isEmpty()) return ChapterReleaseEvidence.eligible(
            baselines[item.key], (baselines[item.key]?.lastReadingAt ?: 0) > 0,
            false, threshold, allowStarted,
        )
        // A trusted match carries reading exclusions to other editions/source copies and public cards.
        return started.all { history ->
            val baseline = baselines[history.key]
            if (baseline == null || baseline.lastReadingAt < history.lastRead) return@all false
            ChapterReleaseEvidence.eligible(
                baseline, true,
                history.progress >= 1f && history.chapters >= (baseline?.total ?: history.chapters),
                threshold, allowStarted,
            )
        }
    }
}
