package org.koitharu.kotatsu.reader.ui.novel

import org.junit.Assert.*
import org.junit.Test

class NovelSpeechTextTest {
    @Test fun longCjkAndEmojiTextIsSplitLosslesslyWithinEngineLimits() {
        val text = "这是一个段落😀".repeat(200)
        val chunks = novelSpeechChunks(text, 0f, 37)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 37 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
    @Test fun readingStartsAtTheVisibleParagraphAndDoesNotMutateProgress() {
        val text = "First paragraph\nВторой абзац\n第三段"
        val ratio = 20f / text.length
        assertEquals("Второй абзац\n第三段", novelSpeechChunks(text, ratio, 4000).joinToString(""))
        assertTrue(novelSpeechChunks(text, 1f, 4000).isEmpty())
        assertTrue(novelSpeechChunks("  ", 0f, 4000).isEmpty())
    }
}
