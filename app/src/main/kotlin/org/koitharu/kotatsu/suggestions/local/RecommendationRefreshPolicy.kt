package org.koitharu.kotatsu.suggestions.local

/** Automatic discovery follows the existing six-hour worker cadence, including failed attempts. */
internal object RecommendationRefreshPolicy {
    const val INTERVAL = 6 * 60 * 60 * 1000L
    const val RETRY_INTERVAL = 60_000L
    const val FAILURE_COOLDOWN = 5 * 60 * 1000L

    fun shouldRefresh(lastRefresh: Long?, retryUntil: Long, now: Long, force: Boolean): Boolean {
        if (retryUntil > now) return false
        if (lastRefresh == null || lastRefresh <= 0) return true
        val interval = if (force) RETRY_INTERVAL else INTERVAL
        return now >= lastRefresh && now - lastRefresh >= interval
    }
}
