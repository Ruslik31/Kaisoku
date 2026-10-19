package org.koitharu.kotatsu.reader.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterBrowserRequestTest {
    @Test fun aResolvedChapterRequiresTheExactCurrentTitleSourceChapterAndUrl() {
        val request = ChapterBrowserRequest(key)
        assertTrue(request.canOpen(key.copy(), resumed = true, focused = true))
        for (other in listOf(null, key.copy(mangaId = 2), key.copy(mangaSource = "other"),
            key.copy(chapterId = 3), key.copy(chapterSource = "other"), key.copy(chapterUrl = "/other/2"))) {
            assertFalse(request.canOpen(other, resumed = true, focused = true))
        }
    }

    @Test fun aPausedOrUnfocusedReaderCannotLaunchTheBrowser() {
        val request = ChapterBrowserRequest(key)
        assertFalse(request.canOpen(key, resumed = false, focused = true))
        assertFalse(request.canOpen(key, resumed = true, focused = false))
    }

    @Test fun pluginResultsMustStillBeValidWebUrls() = runTest {
        val request = ChapterBrowserRequest(key)
        assertEquals("https://fixture.invalid/read/2", request.resolve { "https://fixture.invalid/read/2" })
        assertNull(request.resolve { "content://book/2" })
        assertNull(request.resolve { "https://user:secret@fixture.invalid/read/2" })
        assertNull(request.resolve { null })
    }

    @Test fun leavingTheReaderCancelsResolutionWithoutPublishingAResult() = runTest {
        var published = false
        val request = ChapterBrowserRequest(key)
        val job = launch {
            request.resolve { awaitCancellation() }
            published = true
        }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(published)
    }

    @Test fun aStalledPluginUrlCallbackHasABoundedTimeout() = runTest {
        val failure = runCatching {
            ChapterBrowserRequest(key).resolve(timeoutMillis = 1000) {
                delay(2000)
                "https://fixture.invalid/read/2"
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }

    private val key = ChapterBrowserKey(1, "fixture", 2, "fixture", "/read/2")
}
