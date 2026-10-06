package org.koitharu.kotatsu.scrobbling.anilist.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.koitharu.kotatsu.scrobbling.common.domain.model.AniListLibraryEntry

/** Collection chunks avoid Page's 5,000-entry depth limit and deduplicate custom-list copies. */
internal suspend fun collectAniListLibraryChunks(owner: Long, fetch: suspend (chunk: Int) -> JSONObject): List<AniListLibraryEntry> {
    val result = linkedMapOf<Long, AniListLibraryEntry>()
    for (chunk in 1..10_000) {
        currentCoroutineContext().ensureActive()
        val response = fetch(chunk)
        currentCoroutineContext().ensureActive()
        val collection = response.getJSONObject("data").getJSONObject("MediaListCollection")
        check(collection.getJSONObject("user").getLong("id") == owner) { "Tracker returned a different account" }
        val groups = collection.getJSONArray("lists")
        var advanced = false
        for (groupIndex in 0 until groups.length()) {
            val entries = groups.getJSONObject(groupIndex).getJSONArray("entries")
            for (index in 0 until entries.length()) {
                val entry = AniListLibraryEntry(entries.getJSONObject(index))
                require(entry.mediaId > 0 && entry.listEntryId in 1..Int.MAX_VALUE.toLong() && entry.title.isNotBlank() && entry.score.isFinite()) {
                    "Malformed AniList library entry"
                }
                if (result.putIfAbsent(entry.mediaId, entry) == null) advanced = true
            }
        }
        if (!collection.getBoolean("hasNextChunk")) return result.values.toList()
        check(advanced) { "AniList pagination did not advance" }
    }
    error("AniList library has too many chunks")
}
