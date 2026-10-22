package org.koitharu.kotatsu.suggestions.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicRecommendationMetadataTest {
    @Test fun novelAndMangaAdaptationsKeepDifferentIdentitiesEvenForSameNameAndProviderId() {
        val manga = ani("MANGA")!!
        val novel = ani("NOVEL")!!
        assertNotEquals(manga.key, novel.key)
        assertEquals(2, RecommendationIdentity.deduplicate(listOf(manga, novel)).size)
        assertEquals(RecommendationKind.NOVEL, novel.manga.recommendationKind())
    }
    @Test fun unknownFormatsAndAnimeDoNotBecomeReadableManga() {
        for (format in listOf("", "TV", "UNKNOWN", "MOVIE")) assertNull(ani(format))
        for (type in listOf("", "Unknown", "Anime")) assertNull(mu(type))
    }
    @Test fun adultFlagAndMangaUpdatesAdultGenresArePreserved() {
        val ani = ani("MANGA", true)!!
        assertEquals(org.koitharu.kotatsu.parsers.model.ContentRating.ADULT, ani.manga.contentRating)
        val mu = mu("Manga", "Adult")!!
        assertEquals(org.koitharu.kotatsu.parsers.model.ContentRating.ADULT, mu.manga.contentRating)
        assertEquals(mapOf("MANGAUPDATES" to "43"), mu.trustedIdentifiers)
    }
    @Test fun validNovelMetadataNeverInventsChapterListsOrTrackingRecords() {
        val novel = mu("Novel", "Fantasy")!!
        assertNull(novel.manga.chapters)
        assertEquals(RecommendationKind.NOVEL, novel.manga.recommendationKind())
        assertEquals(setOf("Fantasy"), novel.manga.tags.map { it.title }.toSet())
    }
    private fun ani(format: String, adult: Boolean = false) = PublicRecommendationMetadata.anilistTitle(
        JSONObject().put("id", 42).put("format", format).put("isAdult", adult)
            .put("title", JSONObject().put("english", "The same work")))
    private fun mu(type: String, genre: String = "Drama") = PublicRecommendationMetadata.muTitle(
        JSONObject().put("series_id", 43).put("type", type).put("title", "The same work")
            .put("genres", org.json.JSONArray().put(JSONObject().put("genre", genre))))
}
