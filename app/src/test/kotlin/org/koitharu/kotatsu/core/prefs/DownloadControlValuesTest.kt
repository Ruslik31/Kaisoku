package org.koitharu.kotatsu.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadControlValuesTest {

    @Test
    fun absentMalformedAndWrongTypeValuesRetainExistingDefaults() {
        val damaged = listOf(null, "", "bad", "-1", "1.5", "999999999999999999999", 3, 1_600L, true,
            setOf("1"), "00", " 0 ")
        for (raw in damaged) {
            assertEquals("image interval: $raw", 0L, DownloadControlValues.requestInterval(raw))
            assertEquals("chapter delay: $raw", 0L, DownloadControlValues.chapterDelay(raw))
            assertEquals("chapter limit: $raw", 0, DownloadControlValues.chapterConcurrency(raw))
        }
    }

    @Test
    fun onlySupportedWireValuesEnableEachControl() {
        for (value in listOf("0", "1600", "3000", "5000")) {
            assertEquals(value.toLong(), DownloadControlValues.requestInterval(value))
        }
        for (value in listOf("0", "1000", "3000", "5000", "10000", "30000")) {
            assertEquals(value.toLong(), DownloadControlValues.chapterDelay(value))
        }
        for (value in listOf("0", "1", "2", "3", "4")) {
            assertEquals(value.toInt(), DownloadControlValues.chapterConcurrency(value))
        }
        assertEquals(0L, DownloadControlValues.requestInterval("1000"))
        assertEquals(0L, DownloadControlValues.chapterDelay("1600"))
        assertEquals(0, DownloadControlValues.chapterConcurrency("5"))
    }

    @Test
    fun globalDefaultPreservesSourceSlowdownAndExtensionsRemainUnpaced() {
        assertEquals(1_600L, DownloadControlValues.effectiveRequestInterval(0L, true))
        assertEquals(0L, DownloadControlValues.effectiveRequestInterval(0L, false))
    }

    @Test
    fun globalImageIntervalAppliesToExtensionsAndCannotWeakenSourceSlowdown() {
        for (interval in listOf(1_600L, 3_000L, 5_000L)) {
            assertEquals(interval, DownloadControlValues.effectiveRequestInterval(interval, true))
            assertEquals(interval, DownloadControlValues.effectiveRequestInterval(interval, false))
        }
        assertEquals(1_600L, DownloadControlValues.effectiveRequestInterval(500L, true))
    }
}
