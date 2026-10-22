package org.koitharu.kotatsu.suggestions.local

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.NsfwSourceOverrides
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import java.util.Locale

class RecommendationContentPolicyTest {
    @After fun resetSourceOverrides() { NsfwSourceOverrides.replaceAll(emptyMap()) }

    @Test fun adultRatingsAreExcludedOnlyWhenTheSettingIsEnabled() {
        val adult = title().copy(contentRating = ContentRating.ADULT)
        assertFalse(RecommendationContentPolicy.allowed(adult, true))
        assertTrue(RecommendationContentPolicy.allowed(adult, false))
    }

    @Test fun adultSourcesAndManualSourceFlagsAreExcluded() {
        assertFalse(RecommendationContentPolicy.allowed(title(source = MangaParserSource.EXHENTAI), true))
        NsfwSourceOverrides.replaceAll(mapOf(TestMangaSource.name to true))
        assertFalse(RecommendationContentPolicy.allowed(title(), true))
    }

    @Test fun clearingAManualSourceFlagDoesNotMakeAnExplicitlyAdultTitleSafe() {
        NsfwSourceOverrides.replaceAll(mapOf(MangaParserSource.EXHENTAI.name to false))
        assertTrue(RecommendationContentPolicy.allowed(title(source = MangaParserSource.EXHENTAI), true))
        assertFalse(RecommendationContentPolicy.allowed(title(source = MangaParserSource.EXHENTAI)
            .copy(contentRating = ContentRating.ADULT), true))
    }

    @Test fun mixedSourceAdultGenresWorkWithoutAContentRating() {
        for (genre in listOf("Adult", "Hentai", "Smut", "Erotica", "18+", "r-18", "NSFW", "Sexual Content")) {
            val manga = title(genre = genre)
            assertNull(manga.contentRating)
            assertFalse(genre, RecommendationContentPolicy.allowed(manga, true))
            assertTrue(RecommendationContentPolicy.allowed(manga, false))
        }
    }

    @Test fun localizedAndFullWidthAdultGenresAreRecognized() {
        for (genre in listOf(" ХЕНТАЙ ", "эротика", "для взрослых", "成人", "成人向け", "色情", "１８＋", "Sexual_Content")) {
            assertFalse(genre, RecommendationContentPolicy.allowed(title(genre = genre), true))
        }
    }

    @Test fun genreKeysWorkWhenTheDisplayLabelIsDifferent() {
        val manga = title().copy(tags = setOf(MangaTag("Fixture category", "hentai", TestMangaSource)))
        assertFalse(RecommendationContentPolicy.allowed(manga, true))
    }

    @Test fun generalMatureAndDemographicGenresDoNotBecomeAdultEvidence() {
        for (genre in listOf("Mature", "Seinen", "Josei", "Romance", "Drama", "Adult protagonist", "Hentai parody discussion")) {
            assertTrue(genre, RecommendationContentPolicy.allowed(title(genre = genre), true))
        }
        assertTrue(RecommendationContentPolicy.allowed(title().copy(title = "Adult life"), true))
        assertTrue(RecommendationContentPolicy.allowed(title().copy(contentRating = ContentRating.SUGGESTIVE), true))
    }

    @Test fun normalizationDoesNotDependOnTheDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertFalse(RecommendationContentPolicy.allowed(title(genre = "HENTAI"), true))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun sparseDiscoveryCannotEraseKnownLibraryAdultMetadata() {
        val card = title()
        val known = card.copy(contentRating = ContentRating.ADULT)
        val restored = RecommendationContentPolicy.withKnownRating(card, known)
        assertFalse(RecommendationContentPolicy.allowed(restored, true))
        assertNull(card.contentRating)
        assertEquals(card.id, restored.id)
        assertEquals(card.url, restored.url)
        assertNull(restored.chapters)
    }

    @Test fun knownAdultGenresAlsoCoverSparseDiscovery() {
        val card = title()
        assertFalse(RecommendationContentPolicy.allowed(
            RecommendationContentPolicy.withKnownRating(card, title(genre = "Smut")), true))
    }

    @Test fun equalNamesAndIdsFromAnotherSourceAreNotRatingEvidence() {
        val other = object : MangaSource { override val name = "OTHER" }
        val card = title()
        val adult = title(source = other).copy(contentRating = ContentRating.ADULT)
        assertEquals(card, RecommendationContentPolicy.withKnownRating(card, adult))
        assertEquals(card, RecommendationContentPolicy.withKnownRating(card, adult.copy(id = 42, source = TestMangaSource)))
    }

    @Test fun manualTitleRatingsAreReadWithoutMutatingLibraryMetadata() {
        val card = title()
        val adult = RecommendationContentPolicy.withKnownRating(card, card, ContentRating.ADULT)
        assertFalse(RecommendationContentPolicy.allowed(adult, true))
        val safe = RecommendationContentPolicy.withKnownRating(adult, adult, ContentRating.SAFE)
        assertTrue(RecommendationContentPolicy.allowed(safe, true))
        assertNull(card.contentRating)
    }

    @Test fun relatedPreferenceCannotEraseAnAdultCachedDuplicate() {
        val sparse = recommendation(title()).copy(provenance = RecommendationProvenance.RELATED)
        val adult = recommendation(title().copy(contentRating = ContentRating.ADULT))
        for (values in listOf(listOf(sparse, adult), listOf(adult, sparse))) {
            val winner = RecommendationIdentity.deduplicate(values).single()
            assertEquals(RecommendationProvenance.RELATED, winner.provenance)
            assertFalse(RecommendationContentPolicy.allowed(winner.manga, true))
        }
    }

    @Test fun trustedPublicAdultRatingSurvivesChoosingTheReadableCopy() {
        val source = recommendation(title()).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val public = publicAdult(RecommendationKind.MANGA)
        val winner = RecommendationIdentity.deduplicate(listOf(public, source)).single()
        assertTrue(winner.target is RecommendationTarget.SourceTitle)
        assertEquals(source.key, winner.key)
        assertFalse(RecommendationContentPolicy.allowed(winner.manga, true))
    }

    @Test fun adultGenresSurviveChoosingASparseRelatedCopyAndCacheRoundTrip() {
        val source = recommendation(title()).copy(provenance = RecommendationProvenance.RELATED)
        val tagged = recommendation(title(genre = "Hentai"))
        val winner = RecommendationIdentity.deduplicate(listOf(tagged, source)).single()
        val cached = RecommendationCodec.decode(RecommendationCodec.encode(winner))!!
        assertFalse(RecommendationContentPolicy.allowed(cached.manga, true))
        assertTrue(RecommendationContentPolicy.allowed(cached.manga, false))
    }

    @Test fun adultEvidenceTraversesSeveralTrustedIdentityLinksBeforeDeduplication() {
        val first = recommendation(title(id = 1)).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val intermediate = recommendation(title(id = 2))
            .copy(trustedIdentifiers = mapOf("ANILIST" to "42", "MANGAUPDATES" to "43"))
        val adult = recommendation(title(id = 3, genre = "Hentai"))
            .copy(trustedIdentifiers = mapOf("MANGAUPDATES" to "43"))
        val values = RecommendationIdentity.deduplicate(listOf(first, intermediate, adult))
        assertTrue(values.isNotEmpty())
        assertTrue(values.none { RecommendationContentPolicy.allowed(it.manga, true) })
    }

    @Test fun unrelatedWorksAndNovelAdaptationsDoNotInheritAdultRatings() {
        val source = recommendation(title()).copy(trustedIdentifiers = mapOf("ANILIST" to "42"))
        val novel = publicAdult(RecommendationKind.NOVEL)
        val sameName = recommendation(title(id = 2).copy(contentRating = ContentRating.ADULT))
        val values = RecommendationIdentity.deduplicate(listOf(source, novel, sameName))
        assertEquals(3, values.size)
        assertTrue(RecommendationContentPolicy.allowed(values.single { it.key == source.key }.manga, true))
    }

    @Test fun diversityAndDetailsRankingCannotReintroduceExcludedWorks() {
        val cards = RecommendationIdentity.deduplicate(listOf(recommendation(title(id = 1, genre = "Adventure")),
            recommendation(title(id = 2, genre = "Hentai")), recommendation(title(id = 3, genre = "Drama"))))
            .filter { RecommendationContentPolicy.allowed(it.manga, true) }
        for (request in listOf(RecommendationRequest.Explore, RecommendationRequest.Details(title(id = 99)))) {
            for (diversity in listOf(0, 50, 100)) {
                val ranked = RecommendationRanker.rank(cards, emptyList(), request, diversity, 1_900_000_000_000)
                assertEquals(setOf(1L, 3L), ranked.map { it.manga.id }.toSet())
            }
        }
    }

    private fun title(id: Long = 1, genre: String? = null, source: MangaSource = TestMangaSource) = Manga(
        id, "Same title", emptySet(), "/$id", "https://fixture.invalid/$id", -1f, null, null,
        genre?.let { setOf(MangaTag(it, it, source)) }.orEmpty(), null, emptySet(), source = source,
    )
    private fun recommendation(manga: Manga) = Recommendation(RecommendationTarget.SourceTitle(manga))
    private fun publicAdult(kind: RecommendationKind): Recommendation {
        val manga = title(source = PublicRecommendationSource("ANILIST", kind)).copy(contentRating = ContentRating.ADULT)
        return Recommendation(RecommendationTarget.PublicTitle("ANILIST", "42", manga),
            trustedIdentifiers = mapOf("ANILIST" to "42"))
    }
}
