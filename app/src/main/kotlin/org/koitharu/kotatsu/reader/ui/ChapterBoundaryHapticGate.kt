package org.koitharu.kotatsu.reader.ui

internal data class PresentedChapter(val mangaId: Long, val chapterId: Long)

/** Effect state only: this never reads or controls progress persistence. */
internal class ChapterBoundaryHapticGate {
    private var previous: PresentedChapter? = null

    fun onPresented(chapter: PresentedChapter?, focused: Boolean): Boolean {
        if (chapter == null) return false
        val old = previous
        previous = chapter
        return focused && old != null && old.mangaId == chapter.mangaId && old.chapterId != chapter.chapterId
    }

    fun reset() {
        previous = null
    }
}
