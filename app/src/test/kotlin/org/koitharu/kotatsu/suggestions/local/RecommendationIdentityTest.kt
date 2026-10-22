package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity

class RecommendationIdentityTest {
    private fun source(id: Long) = Recommendation(RecommendationTarget.SourceTitle(
        Manga(id, "Same name", emptySet(), "/$id", "https://fixture.invalid/$id", -1f, null, null,
            emptySet(), null, emptySet(), source = TestMangaSource)))

    @Test fun namesAloneNeverMergeTitles() {
        assertEquals(2, RecommendationIdentity.deduplicate(listOf(source(1), source(2))).size)
    }

    @Test fun trustedProviderIdsMergeIntoTheReadableSource() {
        val readable = source(1).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val public = source(2).let { it.copy(target = RecommendationTarget.PublicTitle("ANILIST", "42",
            it.manga.copy(source = PublicRecommendationSource("ANILIST", RecommendationKind.MANGA))),
            trustedIdentifiers = mapOf("ANILIST" to "42")) }
        assertEquals(listOf(readable), RecommendationIdentity.deduplicate(listOf(public, readable)))
    }

    @Test fun novelAndMangaAdaptationsStaySeparateEvenWithBadSharedIds() {
        val manga = source(1).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val novel = source(2).let { it.copy(target = RecommendationTarget.PublicTitle("ANILIST", "42",
            it.manga.copy(source = PublicRecommendationSource("ANILIST", RecommendationKind.NOVEL))),
            trustedIdentifiers = mapOf("ANILIST" to "42")) }
        assertEquals(2, RecommendationIdentity.deduplicate(listOf(manga, novel)).size)
    }

    @Test fun feedbackHidesOnlyTheSameWorkAndCanBeUndone() {
        val value = source(1).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val dismissals = RecommendationIdentity.feedbackKeys(value).toMutableSet()
        assertTrue(RecommendationIdentity.feedbackKeys(value).any { it in dismissals })
        assertFalse(RecommendationIdentity.feedbackKeys(source(2)).any { it in dismissals })
        dismissals.removeAll(RecommendationIdentity.feedbackKeys(value))
        assertTrue(dismissals.isEmpty())
    }

    @Test fun sourceRelationshipsWinOverTheirStaleCachedDuplicate() {
        val cached = source(1)
        val related = cached.copy(provenance = RecommendationProvenance.RELATED)
        assertEquals(listOf(related), RecommendationIdentity.deduplicate(listOf(cached, related)))
    }

    @Test fun readingUpdatesAndOrderingDoNotInvalidateTrackerIdentity() {
        val first = ScrobblingEntity(2, 0, 1, 42, "CURRENT", 10, "note", 4f, 7)
        val second = ScrobblingEntity(2, 0, 2, 43, null, 0, null, 0f, 7)
        val progressed = ScrobblingEntity(2, 0, 1, 42, "COMPLETED", 50, "edited", 9f, 7)
        assertEquals(RecommendationIdentity.trackerVersion(listOf(first, second)),
            RecommendationIdentity.trackerVersion(listOf(second, progressed)))
    }

    @Test fun correctedMatchesAndAccountChangesInvalidateTrackerIdentity() {
        val first = ScrobblingEntity(2, 0, 1, 42, null, 0, null, 0f, 7)
        val corrected = ScrobblingEntity(2, 0, 1, 43, null, 0, null, 0f, 7)
        val otherAccount = ScrobblingEntity(2, 0, 1, 42, null, 0, null, 0f, 8)
        assertNotEquals(RecommendationIdentity.trackerVersion(listOf(first)),
            RecommendationIdentity.trackerVersion(listOf(corrected)))
        assertNotEquals(RecommendationIdentity.trackerVersion(listOf(first)),
            RecommendationIdentity.trackerVersion(listOf(otherAccount)))
    }
}
