package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.strings
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerTimestamp

internal data class TrackerLibraryPage(val entries: List<TrackerLibraryEntry>, val count: Int, val hasNext: Boolean)

internal suspend fun collectTrackerLibraryPages(fetch: suspend (page: Int, offset: Int) -> TrackerLibraryPage): List<TrackerLibraryEntry> {
    val result = linkedMapOf<Long, TrackerLibraryEntry>()
    var offset = 0
    for (page in 1..10_000) {
        currentCoroutineContext().ensureActive()
        val batch = fetch(page, offset)
        currentCoroutineContext().ensureActive()
        require(batch.entries.all { it.mediaId > 0 && it.listEntryId > 0 && it.title.isNotBlank() && it.score.isFinite() }) {
            "Malformed tracker library entry"
        }
        val fresh = batch.entries.any { it.mediaId !in result }
        batch.entries.forEach { result.putIfAbsent(it.mediaId, it) }
        if (!batch.hasNext) return result.values.toList()
        check(batch.count > 0 && fresh) { "Tracker pagination did not advance" }
        offset += batch.count
    }
    error("Tracker library has too many pages")
}

internal fun malLibraryPage(response: JSONObject): TrackerLibraryPage {
    val rows = response.getJSONArray("data")
    val entries = (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        val media = row.getJSONObject("node")
        val entry = row.getJSONObject("list_status")
        val id = media.getLong("id")
        val names = media.optJSONObject("alternative_titles")
        TrackerLibraryEntry(ScrobblerService.MAL, id, id.toInt(), media.getString("title"),
            media.optJSONObject("main_picture")?.getStringOrNull("large").orEmpty(), "https://myanimelist.net/manga/$id",
            trackerStatus(ScrobblerService.MAL, entry.getString("status"), entry.optBoolean("is_rereading")),
            entry.getInt("num_chapters_read"), media.optInt("num_chapters").takeIf { it > 0 },
            entry.optDouble("score", 0.0).toFloat().coerceIn(0f, 10f), entry.getStringOrNull("comments"),
            trackerTimestamp(entry.getStringOrNull("updated_at")),
            names?.optJSONArray("synonyms").strings() + listOfNotNull(names?.getStringOrNull("en"), names?.getStringOrNull("ja")),
            media.getStringOrNull("media_type"))
    }
    return TrackerLibraryPage(entries, rows.length(), response.optJSONObject("paging")?.getStringOrNull("next") != null)
}

internal fun kitsuLibraryPage(response: JSONObject): TrackerLibraryPage {
    val included = response.optJSONArray("included")
    val mediaById = (0 until (included?.length() ?: 0)).map { included!!.getJSONObject(it) }
        .filter { it.optString("type") == "manga" }.associateBy { it.getString("id") }
    val rows = response.getJSONArray("data")
    val entries = (0 until rows.length()).map { i ->
        val entry = rows.getJSONObject(i)
        val id = entry.getJSONObject("relationships").getJSONObject("manga").getJSONObject("data").getString("id")
        val media = mediaById[id] ?: error("Kitsu library is missing included manga metadata")
        val attrs = media.getJSONObject("attributes")
        val info = entry.getJSONObject("attributes")
        val titles = attrs.getJSONObject("titles")
        val names = titles.keys().asSequence().mapNotNull { titles.getStringOrNull(it) }.filter(String::isNotBlank).toSet()
        TrackerLibraryEntry(ScrobblerService.KITSU, id.toLong(), entry.getInt("id"),
            attrs.getStringOrNull("canonicalTitle") ?: names.first(), attrs.optJSONObject("posterImage")?.getStringOrNull("medium").orEmpty(),
            "https://kitsu.app/manga/${attrs.getString("slug")}",
            trackerStatus(ScrobblerService.KITSU, info.getString("status"), info.optBoolean("reconsuming")),
            info.optInt("progress"), attrs.optInt("chapterCount").takeIf { it > 0 },
            (info.optDouble("ratingTwenty", 0.0) / 2).toFloat().coerceIn(0f, 10f), info.getStringOrNull("notes"),
            trackerTimestamp(info.getStringOrNull("updatedAt")), names, attrs.getStringOrNull("subtype"))
    }
    return TrackerLibraryPage(entries, rows.length(), response.optJSONObject("links")?.getStringOrNull("next") != null)
}

internal fun shikimoriLibraryPage(rows: JSONArray): TrackerLibraryPage {
    val entries = (0 until rows.length()).map { i ->
        val entry = rows.getJSONObject(i)
        val media = entry.getJSONObject("manga")
        fun absolute(path: String) = if (path.startsWith("/")) "https://shikimori.io$path" else path
        TrackerLibraryEntry(ScrobblerService.SHIKIMORI, media.getLong("id"), entry.getInt("id"),
            media.getString("name"), absolute(media.getJSONObject("image").getStringOrNull("original").orEmpty()),
            absolute(media.getString("url")), trackerStatus(ScrobblerService.SHIKIMORI, entry.getString("status")),
            entry.getInt("chapters"), media.optInt("chapters").takeIf { it > 0 }, entry.optDouble("score", 0.0).toFloat().coerceIn(0f, 10f),
            entry.getStringOrNull("text"), trackerTimestamp(entry.getStringOrNull("updated_at")),
            setOfNotNull(media.getStringOrNull("russian")), media.getStringOrNull("kind"))
    }
    return TrackerLibraryPage(entries, rows.length(), rows.length() == 100)
}

internal fun mangaBakaLibraryPage(response: JSONObject, metadata: Map<Long, JSONObject>): TrackerLibraryPage {
    val rows = response.getJSONArray("data")
    val entries = (0 until rows.length()).map { i ->
        val entry = rows.getJSONObject(i)
        val embedded = entry.optJSONObject("series") ?: entry.optJSONObject("Series") ?: entry
        val id = entry.optLong("series_id", embedded.optLong("id", 0L))
        val media = metadata[id] ?: embedded
        val title = media.getStringOrNull("title") ?: media.getStringOrNull("romanized_title") ?: media.getStringOrNull("native_title")
            ?: error("MangaBaka library is missing series metadata")
        TrackerLibraryEntry(ScrobblerService.MANGABAKA, id, id.toInt(), title,
            media.optJSONObject("cover")?.optJSONObject("x250")?.getStringOrNull("x1").orEmpty(), "https://mangabaka.org/$id",
            trackerStatus(ScrobblerService.MANGABAKA, entry.getString("state")),
            entry.optDouble("progress_chapter", 0.0).takeIf(Double::isFinite)?.toInt() ?: 0,
            media.optInt("total_chapters").takeIf { it > 0 },
            (entry.optDouble("rating", 0.0) / 10).toFloat().coerceIn(0f, 10f), entry.getStringOrNull("note"),
            trackerTimestamp(entry.getStringOrNull("updated_at")), setOfNotNull(media.getStringOrNull("native_title"),
                media.getStringOrNull("romanized_title")), media.getStringOrNull("type"))
    }
    return TrackerLibraryPage(entries, rows.length(), response.getJSONObject("pagination").getStringOrNull("next") != null)
}
