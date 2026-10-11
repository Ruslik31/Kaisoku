package org.koitharu.kotatsu.suggestions.local

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

internal object PublicRecommendationMetadata {
    fun anilistTitle(data: JSONObject): Recommendation? {
        if (data.optString("format") !in setOf("MANGA", "ONE_SHOT", "NOVEL")) return null
        val kind = if (data.optString("format") == "NOVEL") RecommendationKind.NOVEL else RecommendationKind.MANGA
        val names = data.optJSONObject("title") ?: return null
        val title = listOf("romaji", "english", "native").mapNotNull { names.optString(it).takeIf(String::isNotEmpty) }
        if (title.isEmpty()) return null
        return publicTitle("ANILIST", data.getLong("id").toString(), kind, title.first(), title.drop(1).toSet(),
            data.optString("siteUrl"), data.optJSONObject("coverImage")?.optString("large"),
            itemsStrings(data.optJSONArray("genres")), data.optBoolean("isAdult"))
    }

    fun muTitle(data: JSONObject): Recommendation? {
        val type = data.optString("type")
        if (type !in setOf("Manga", "Manhwa", "Manhua", "Novel", "OEL", "Doujinshi", "Artbook")) return null // Do not infer adaptations from a matching title.
        val kind = if (type == "Novel") RecommendationKind.NOVEL else RecommendationKind.MANGA
        val genres = items(data.optJSONArray("genres") ?: JSONArray()).mapNotNull { it.optString("genre").takeIf(String::isNotEmpty) }.toSet()
        val title = data.optString("title").takeIf(String::isNotEmpty) ?: return null
        return publicTitle("MANGAUPDATES", data.getLong("series_id").toString(), kind, title, emptySet(), data.optString("url"),
            data.optJSONObject("image")?.optJSONObject("url")?.optString("original"), genres,
            genres.any { it.equals("Adult", true) || it.equals("Hentai", true) })
    }

    private fun publicTitle(provider: String, id: String, kind: RecommendationKind, title: String, aliases: Set<String>,
        url: String, cover: String?, genres: Set<String>, adult: Boolean): Recommendation {
        val source = PublicRecommendationSource(provider, kind)
        val manga = Manga(("${source.name}:$id").fold(-1125899906842597L) { hash, c -> 31 * hash + c.code }, title, aliases,
            id, url, -1f, if (adult) ContentRating.ADULT else null, cover,
            genres.mapTo(linkedSetOf()) { MangaTag(it, it, source) }, null, emptySet(), source = source)
        return Recommendation(RecommendationTarget.PublicTitle(provider, id, manga), provenance =
            if (provider == "ANILIST") RecommendationProvenance.ANILIST else RecommendationProvenance.MANGAUPDATES,
            trustedIdentifiers = mapOf(provider to id))
    }
    private fun items(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    private fun itemsStrings(array: JSONArray?): Set<String> = if (array == null) emptySet() else
        (0 until array.length()).mapNotNullTo(linkedSetOf()) { array.optString(it).takeIf(String::isNotEmpty) }

}
