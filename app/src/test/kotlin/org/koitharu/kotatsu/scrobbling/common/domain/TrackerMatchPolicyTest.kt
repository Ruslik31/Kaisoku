package org.koitharu.kotatsu.scrobbling.common.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga

class TrackerMatchPolicyTest {
    @Test fun trustedIdentifiersRequireCanonicalHttpsHostAndMangaPath() {
        assertEquals(30013L, canonicalTrackerId("https://anilist.co/manga/30013/One-Piece/", "anilist.co"))
        assertEquals(2L, canonicalTrackerId("https://www.myanimelist.net/manga/2", "myanimelist.net"))
        listOf("https://anilist.co.evil.org/manga/1", "https://evil.org/?next=https://anilist.co/manga/1",
            "https://anilist.co@evil.org/manga/1", "https://anilist.co/anime/1", "http://anilist.co/manga/1",
            "https://anilist.co:8080/manga/1", "https://anilist.co/manga/0").forEach { assertNull(canonicalTrackerId(it, "anilist.co")) }
    }

    @Test fun sequelsSimilarNamesAndDuplicateNamesCannotBeSilentlyChosen() {
        val original = candidate(1, "My Story"); val sequel = candidate(2, "My Story 2")
        assertEquals(1L, uniqueTitleSuggestion(setOf("MY STORY"), listOf(original, sequel)))
        assertNull(uniqueTitleSuggestion(setOf("My Story"), listOf(sequel)))
        assertNull(uniqueTitleSuggestion(setOf("My Story"), listOf(original, candidate(3, "My Story"))))
    }

    @Test fun aliasesAndUnicodeNormalizationImproveSuggestionsWithoutTrustingThem() {
        assertEquals(1L, uniqueTitleSuggestion(setOf("我的狐仙老婆"), listOf(candidate(1, "My Fox Immortal Wife", "我的狐仙老婆"))))
        assertEquals(2L, uniqueTitleSuggestion(setOf("ＡＢＣ"), listOf(candidate(2, "ABC"))))
        assertNull(uniqueTitleSuggestion(setOf("A"), listOf(candidate(3, "A"))))
    }

    private fun candidate(id: Long, name: String, alias: String? = null) = ScrobblerManga(id, name, alias, null, "", false)
}
