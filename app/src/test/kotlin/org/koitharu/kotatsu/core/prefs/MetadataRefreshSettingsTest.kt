package org.koitharu.kotatsu.core.prefs

import org.junit.Assert.*
import org.junit.Test

class MetadataRefreshSettingsTest {
    @Test fun oldTrueAndFalseFlagsCopyToTheActualCanonicalIds() {
        val values = mapOf(key(1) to true, key(2) to false, "unrelated" to true)
        assertEquals(mapOf(key(10) to true, key(20) to false),
            missingMergedMetadataFlags(values, mapOf(1L to 10L, 2L to 20L)))
        assertEquals(mapOf(key(1) to true, key(2) to false, "unrelated" to true), values)
    }

    @Test fun explicitTargetTrueAndFalseBothWin() {
        for (old in listOf(true, false)) for (target in listOf(true, false)) {
            assertTrue(missingMergedMetadataFlags(mapOf(key(1) to old, key(10) to target), mapOf(1L to 10L)).isEmpty())
        }
    }

    @Test fun missingFlagsAndUnrelatedIdsAreNotInvented() {
        assertTrue(missingMergedMetadataFlags(mapOf(key(2) to true), mapOf(1L to 10L)).isEmpty())
        assertTrue(missingMergedMetadataFlags(mapOf(key(1) to "true"), mapOf(1L to 10L)).isEmpty())
        assertTrue(missingMergedMetadataFlags(mapOf(key(1) to false), mapOf(1L to 1L)).isEmpty())
    }

    @Test fun severalOldRowsHaveAStableNonReplacingWinner() {
        val values = mapOf(key(1) to false, key(2) to true)
        assertEquals(mapOf(key(10) to false), missingMergedMetadataFlags(values, linkedMapOf(2L to 10L, 1L to 10L)))
    }

    private fun key(id: Long) = MetadataRefreshSettings.titleKey(id)

    @Test fun chainsCopyOnlyToTheFinalCanonicalTitleWithTheClosestExplicitChoice() {
        val remaps = linkedMapOf(1L to 2L, 2L to 3L)
        assertEquals(mapOf(key(3) to true), missingMergedMetadataFlags(mapOf(key(1) to true), remaps))
        assertEquals(mapOf(key(3) to false),
            missingMergedMetadataFlags(mapOf(key(1) to true, key(2) to false), remaps))
        assertTrue(missingMergedMetadataFlags(mapOf(key(1) to true, key(3) to false), remaps).isEmpty())
    }

    @Test fun cyclesAndTheirIncomingMappingsDoNotInventACanonicalIdentity() {
        assertTrue(missingMergedMetadataFlags(mapOf(key(1) to true, key(2) to false),
            mapOf(1L to 2L, 2L to 3L, 3L to 2L)).isEmpty())
    }
}
