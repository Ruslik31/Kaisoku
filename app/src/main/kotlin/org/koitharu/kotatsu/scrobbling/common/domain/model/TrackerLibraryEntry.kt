package org.koitharu.kotatsu.scrobbling.common.domain.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class TrackerLibraryEntry(
    val service: ScrobblerService,
    val mediaId: Long,
    val listEntryId: Int,
    val title: String,
    val coverUrl: String,
    val url: String,
    val status: String,
    val progress: Int,
    val chapters: Int?,
    val score: Float,
    val notes: String?,
    val updatedAt: Long,
    val aliases: Set<String> = emptySet(),
    val format: String? = null,
    val localMangaId: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("service", service.name); put("mediaId", mediaId); put("listEntryId", listEntryId)
        put("title", title); put("coverUrl", coverUrl); put("url", url); put("status", status)
        put("progress", progress); put("chapters", chapters); put("score", score.toDouble())
        put("notes", notes); put("updatedAt", updatedAt); put("aliases", JSONArray(aliases.toList()))
        put("format", format); put("localMangaId", localMangaId)
    }

    companion object {
        fun fromJson(json: JSONObject): TrackerLibraryEntry = TrackerLibraryEntry(
            service = ScrobblerService.valueOf(json.getString("service")), mediaId = json.getLong("mediaId"),
            listEntryId = json.getInt("listEntryId"), title = json.getString("title"),
            coverUrl = json.optString("coverUrl"), url = json.optString("url"), status = json.getString("status"),
            progress = json.getInt("progress"), chapters = json.optInt("chapters").takeIf { it > 0 },
            score = json.optDouble("score", 0.0).toFloat(), notes = json.optString("notes").takeIf { !json.isNull("notes") },
            updatedAt = json.optLong("updatedAt"), aliases = json.optJSONArray("aliases").strings(),
            format = json.optString("format").takeIf { !json.isNull("format") },
            localMangaId = json.optLong("localMangaId").takeIf { !json.isNull("localMangaId") && json.has("localMangaId") },
        )
    }
}

internal fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else
    (0 until length()).mapNotNull { optString(it).takeIf { text -> !isNull(it) && text.isNotBlank() } }.toSet()

internal fun trackerTimestamp(value: String?): Long = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

internal fun trackerStatus(service: ScrobblerService, raw: String, repeating: Boolean = false): String {
    if (repeating) return "REPEATING"
    return when (service) {
        ScrobblerService.ANILIST -> raw
        else -> when (raw.lowercase()) {
            "reading", "watching", "current" -> "CURRENT"
            "completed" -> "COMPLETED"
            "planned", "plan_to_read", "plan_to_watch", "planning", "considering" -> "PLANNING"
            "on_hold", "on-hold", "paused" -> "PAUSED"
            "dropped" -> "DROPPED"
            "rereading", "re-reading", "rewatching", "repeating", "reconsuming" -> "REPEATING"
            else -> raw
        }
    }
}

internal object TrackerLibraryFilter {
    fun apply(entries: List<TrackerLibraryEntry>, status: String?, query: String, sortByTitle: Boolean): List<TrackerLibraryEntry> =
        entries.filter { (status == null || it.status == status) &&
            (query.isBlank() || (it.aliases + it.title).any { name -> name.contains(query, ignoreCase = true) }) }
            .let { if (sortByTitle) it.sortedBy { entry -> entry.title.lowercase() } else it.sortedByDescending { entry -> entry.updatedAt } }
}
