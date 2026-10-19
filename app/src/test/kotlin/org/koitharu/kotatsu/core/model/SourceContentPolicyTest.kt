package org.koitharu.kotatsu.core.model

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.ContentType

class SourceContentPolicyTest {
    @After fun resetOverrides() = NsfwSourceOverrides.replaceAll(emptyMap())

    @Test fun disabledFeatureExactlyRetainsLegacyRestrictionForEveryClassAndSelection() {
        for (restricted in listOf(false, true)) for (selected in listOf(emptySet(), SourceContentClass.entries.toSet())) {
            val policy = SourceContentPolicy(false, selected, restricted)
            for (contentClass in SourceContentClass.entries) for (flag in listOf(false, true)) {
                assertEquals(!restricted || !flag, policy.allows(contentClass, flag))
            }
        }
    }

    @Test fun everyCombinationOfClassesIsIndependentFromLegacyFlagAndRestriction() {
        for (bits in 0..7) {
            val selected = SourceContentClass.entries.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.toSet()
            for (restricted in listOf(false, true)) for (flag in listOf(false, true)) {
                val policy = SourceContentPolicy(true, selected, restricted)
                for (contentClass in SourceContentClass.entries) assertEquals(contentClass in selected, policy.allows(contentClass, flag))
            }
        }
    }

    @Test fun manualSourceOverridesTakePrecedenceWithoutChangingIdentityOrPrivacyFlags() {
        val mixed = source(SourceContentClass.MIXED)
        val name = mixed.name
        assertEquals(SourceContentClass.MIXED, mixed.contentClass())
        assertTrue(mixed.isNsfw())
        NsfwSourceOverrides.replaceAll(mapOf(name to false))
        assertEquals(SourceContentClass.SAFE, mixed.contentClass())
        assertFalse(mixed.isNsfw())
        NsfwSourceOverrides.replaceAll(mapOf(name to true))
        assertEquals(SourceContentClass.ADULT, mixed.contentClass())
        assertEquals(name, mixed.name)
        assertEquals(SourceContentClass.MIXED, mixed.contentClass)
    }

    @Test fun nativeSourcesKeepTheirDeclaredAdultAndSafeClasses() {
        val adult = MangaParserSource.entries.first { it.contentType == ContentType.HENTAI }
        val safe = MangaParserSource.entries.first { it.contentType == ContentType.MANGA }
        assertEquals(SourceContentClass.ADULT, adult.contentClass())
        assertEquals(SourceContentClass.SAFE, safe.contentClass())
        NsfwSourceOverrides.replaceAll(mapOf(adult.name to false, safe.name to true))
        assertEquals(SourceContentClass.SAFE, adult.contentClass())
        assertEquals(SourceContentClass.ADULT, safe.contentClass())
    }

    @Test fun mixedDoesNotMakeEveryTitleAdultInSeparateModeButPrivacyRemainsConservative() {
        val title = manga(source(SourceContentClass.MIXED))
        assertTrue(title.isNsfw())
        assertTrue(title.isNsfwForFiltering(false))
        assertFalse(title.isNsfwForFiltering(true))
        assertTrue(title.copy(contentRating = ContentRating.ADULT).isNsfwForFiltering(true))
        assertTrue(manga(source(SourceContentClass.ADULT)).isNsfwForFiltering(true))
    }

    @Test fun manifestWarningsPreserveExplicitMixedAndRestrictiveConflicts() {
        for (value in listOf(1, 1L, 1f, "1")) assertEquals(SourceContentClass.MIXED, manifestContentClass(value, true))
        for (value in listOf(2, "2")) assertEquals(SourceContentClass.ADULT, manifestContentClass(value, false))
        assertEquals(SourceContentClass.SAFE, manifestContentClass(0, false))
        for (legacy in listOf(true, 1, "true", "1")) assertEquals(SourceContentClass.ADULT, manifestContentClass(0, legacy))
        for (value in listOf(3, "3", 1.5, true)) assertEquals(SourceContentClass.ADULT, manifestContentClass(value, false))
        assertEquals(SourceContentClass.ADULT, manifestContentClass("broken", true))
        assertEquals(SourceContentClass.SAFE, manifestContentClass(null, null))
    }

    private fun source(contentClass: SourceContentClass) = MihonMangaSource("fixture.policy", 928,
        isNsfwSource = contentClass != SourceContentClass.SAFE, contentClass = contentClass)

    private fun manga(source: MihonMangaSource) = Manga(1, "Title", emptySet(), "/1", "https://fixture.invalid/1",
        -1f, null, null, emptySet(), null, emptySet(), source = source)
}
