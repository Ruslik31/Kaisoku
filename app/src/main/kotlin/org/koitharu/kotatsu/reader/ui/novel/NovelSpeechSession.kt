package org.koitharu.kotatsu.reader.ui.novel

/** Speech has its own cursor. It never writes the reader/history cursor. */
internal class NovelSpeechSession {
    var generation = 0L
        private set
    var chapter = 0
        private set
    var chunk = 0
        private set
    var offset = 0
        private set

    val utteranceId: String get() = "$generation:$chapter:$chunk"
    fun accepts(id: String?) = id == utteranceId
    fun invalidate() { generation++ }
    fun seekChapter(index: Int) { invalidate(); chapter = index; chunk = 0; offset = 0 }
    fun advanceChunk() { chunk++; offset = 0 }
    fun rememberOffset(id: String?, value: Int, length: Int) {
        if (accepts(id)) offset = value.coerceIn(offset, length)
    }
}

/** A dying service must not retire a new voice picker or another request started meanwhile. */
internal class NovelSpeechServiceOwnership {
    private var request = 0L
    fun newRequest(): Long = ++request
    fun attach(): Long = request
    fun owns(token: Long?): Boolean = token == request
}
