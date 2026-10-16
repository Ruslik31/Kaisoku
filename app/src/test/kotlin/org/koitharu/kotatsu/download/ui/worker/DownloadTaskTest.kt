package org.koitharu.kotatsu.download.ui.worker

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import java.io.File

class DownloadTaskTest {
    @Test fun retryRoundTripPreservesMeteringDestinationFormatSelectionAndSilence() {
        for (metered in listOf(false, true)) for (format in DownloadFormat.entries) {
            val original = DownloadTask(42, true, true, longArrayOf(4, 7), File("chapter-output"), format, metered)
            val retry = DownloadTask(original.toData()).forRetry()
            assertFalse(retry.isPaused)
            assertEquals(42L, retry.mangaId)
            assertTrue(retry.isSilent)
            assertArrayEquals(original.chaptersIds, retry.chaptersIds)
            assertNotSame(original.chaptersIds, retry.chaptersIds)
            assertEquals(original.destination, retry.destination)
            assertEquals(format, retry.format)
            assertEquals(metered, retry.allowMeteredNetwork)
            assertTrue(original.isPaused)
        }
    }
    @Test fun networkConstraintUpdatePreservesPauseAndTaskInput() {
        val original = DownloadTask(42, true, true, longArrayOf(4, 7), File("chapter-output"), DownloadFormat.entries.first(), true)
        val changed = DownloadTask(original.withMeteredNetwork(false).toData())
        assertTrue(changed.isPaused)
        assertTrue(changed.isSilent)
        assertEquals(42L, changed.mangaId)
        assertArrayEquals(original.chaptersIds, changed.chaptersIds)
        assertEquals(original.destination, changed.destination)
        assertEquals(original.format, changed.format)
        assertFalse(changed.allowMeteredNetwork)
    }
    @Test fun legacyTaskCanRecoverEffectiveWifiConstraintFromWorkInfo() {
        val legacy = DownloadTask(androidx.work.Data.Builder().putLong("manga_id", 4).build())
        assertTrue(legacy.allowMeteredNetwork)
        assertFalse(legacy.forRetry(allowMeteredNetwork = false).allowMeteredNetwork)
    }
    @Test fun unspecifiedChapterSelectionAndFormatRemainUnspecified() {
        val task = DownloadTask(1, false, false, null, null, null, false)
        assertEquals(task, DownloadTask(task.forRetry().toData()))
    }
}
