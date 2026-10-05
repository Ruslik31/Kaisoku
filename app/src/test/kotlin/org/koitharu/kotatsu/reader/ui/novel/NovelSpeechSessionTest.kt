package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.*
import org.junit.Test

class NovelSpeechSessionTest {
    @Test fun delayedServiceStartsCannotAttachToAReplacementVoicePicker() {
        val ownership = NovelSpeechServiceOwnership()
        val queuedStart = ownership.newRequest()
        val replacement = ownership.newRequest()
        assertFalse(ownership.owns(queuedStart))
        assertTrue(ownership.owns(replacement))
        assertFalse(ownership.owns(-1L))
        assertFalse(ownership.owns(null))
    }

    @Test fun oldServiceDestructionCannotStopANewRequest() {
        val ownership = NovelSpeechServiceOwnership()
        val firstRequest = ownership.newRequest()
        val firstService = ownership.attach()
        assertEquals(firstRequest, firstService)
        assertTrue(ownership.owns(firstService))
        ownership.newRequest()
        assertFalse(ownership.owns(firstService))
        assertFalse(ownership.owns(null))
        assertTrue(ownership.owns(ownership.attach()))
    }

    @Test fun pausedOrReplacedUtterancesCannotAdvanceTheNewSession() {
        val cursor = NovelSpeechSession()
        cursor.seekChapter(2)
        val oldId = cursor.utteranceId
        cursor.rememberOffset(oldId, 15, 100)
        cursor.invalidate()
        assertFalse(cursor.accepts(oldId))
        cursor.rememberOffset(oldId, 70, 100)
        assertEquals(15, cursor.offset)
        cursor.seekChapter(3)
        assertEquals(3, cursor.chapter)
        assertEquals(0, cursor.chunk)
        assertEquals(0, cursor.offset)
        assertFalse(cursor.accepts(oldId))
    }

    @Test fun engineRangesAreMonotonicAndChunkCompletionResetsOnlySpeechOffset() {
        val cursor = NovelSpeechSession()
        cursor.seekChapter(365)
        cursor.rememberOffset(cursor.utteranceId, 21, 100)
        cursor.rememberOffset(cursor.utteranceId, 2, 100)
        assertEquals(21, cursor.offset)
        cursor.rememberOffset(cursor.utteranceId, 999, 100)
        assertEquals(100, cursor.offset)
        val completed = cursor.utteranceId
        cursor.advanceChunk()
        assertEquals(365, cursor.chapter)
        assertEquals(1, cursor.chunk)
        assertEquals(0, cursor.offset)
        assertFalse(cursor.accepts(completed))
    }
}
