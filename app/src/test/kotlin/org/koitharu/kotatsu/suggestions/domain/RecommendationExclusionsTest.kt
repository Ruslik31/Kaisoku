package org.koitharu.kotatsu.suggestions.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

class RecommendationExclusionsTest {
    @Test fun namesUseUnicodeCaseAndWhitespaceWithoutFuzzyGenreMatches() {
        assertTrue(RecommendationTagExclusions.contains(setOf("ＳＣＩ—ＦＩ"), " sci fi "))
        assertTrue(RecommendationTagExclusions.contains(setOf("我的狐仙"), " 我的狐仙 "))
        assertTrue(RecommendationTagExclusions.contains(setOf("حكاية الليل"), "حكاية  الليل"))
        assertFalse(RecommendationTagExclusions.contains(setOf("Action"), "Martial action"))
        assertFalse(RecommendationTagExclusions.contains(setOf("Drama"), "Dramatic comedy"))
        assertFalse(RecommendationTagExclusions.contains(setOf(""), ""))
    }

    @Test fun editingPreservesSpellingAndIsIdempotent() {
        val original = linkedSetOf("SCI-FI", "Drama")
        val once = RecommendationTagExclusions.update(original, " sci fi ", true)
        assertEquals(original, once)
        assertEquals(once, RecommendationTagExclusions.update(once, "SCI FI", true))
        assertEquals(setOf("Drama"), RecommendationTagExclusions.update(once, "sci fi", false))
        assertEquals(setOf("Drama"), RecommendationTagExclusions.update(setOf("Drama"), "sci fi", false))
        assertEquals(setOf("SCI-FI", "Drama"), original)
    }

    @Test fun unrepresentableNamesCannotCorruptTheCommaSeparatedPreference() {
        for (name in listOf("", "  ", "...", "Drama, Action")) {
            assertFalse(RecommendationTagExclusions.isValid(name))
            assertThrows(IllegalArgumentException::class.java) {
                RecommendationTagExclusions.update(setOf("Romance"), name, true)
            }
        }
    }

    @Test fun cachedLegacyTitlesReappearAfterUndoWithoutFetchingAgainAndLimitFollowsExclusions() = runTest {
        val blocked = title(1, "Drama")
        val allowed = title(2, "Adventure")
        val adult = title(3, "Adventure").copy(contentRating = ContentRating.ADULT)
        var requests = 0
        val candidates = flow { requests++; emit(listOf(blocked, allowed, adult)) }
        val policy = MutableStateFlow(RecommendationExclusions(setOf("Drama"), true))
        val emissions = mutableListOf<List<Manga>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            candidates.withRecommendationExclusions(policy, 1).collect { emissions.add(it) }
        }
        testScheduler.runCurrent()
        assertEquals(listOf(allowed), emissions.last())
        policy.value = RecommendationExclusions(setOf("Adventure"), true)
        testScheduler.runCurrent()
        assertEquals(listOf(blocked), emissions.last())
        policy.value = RecommendationExclusions(emptySet(), false)
        testScheduler.runCurrent()
        assertEquals(listOf(blocked), emissions.last())
        assertEquals(1, requests)
        assertNull(blocked.chapters)
    }

    @Test fun adultAndGenrePoliciesAreIndependentAndMissingMetadataIsNotInvented() = runTest {
        val unknown = title(1, "")
        val adult = title(2, "Drama").copy(contentRating = ContentRating.ADULT)
        val policy = MutableStateFlow(RecommendationExclusions(setOf("Drama"), false))
        val emissions = mutableListOf<List<Manga>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow { emit(listOf(unknown, adult)) }.withRecommendationExclusions(policy).collect { emissions.add(it) }
        }
        testScheduler.runCurrent()
        assertEquals(listOf(unknown), emissions.last())
        policy.value = RecommendationExclusions(emptySet(), false)
        testScheduler.runCurrent()
        assertEquals(listOf(unknown, adult), emissions.last())
        policy.value = RecommendationExclusions(emptySet(), true)
        testScheduler.runCurrent()
        assertEquals(listOf(unknown), emissions.last())
    }

    private fun title(id: Long, genre: String): Manga = Manga(id, "Title $id", emptySet(), "/$id",
        "https://fixture.invalid/$id", -1f, null, null,
        if (genre.isEmpty()) emptySet() else setOf(MangaTag(genre, genre, TestMangaSource)),
        null, emptySet(), source = TestMangaSource)
}
