package org.koitharu.kotatsu.reader.ui.novel

/** Start at the visible paragraph and respect engine limits without splitting surrogate pairs. */
internal fun novelSpeechChunks(text: String, ratio: Float, limit: Int): List<String> {
    require(limit >= 2)
    if (text.isBlank()) return emptyList()
    val position = (text.length * ratio.coerceIn(0f, 1f)).toInt().coerceIn(0, text.length)
    var start = if (position == text.length) position else text.lastIndexOf('\n', position - 1) + 1
    val result = ArrayList<String>()
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo start + limit / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        text.substring(start, end).takeIf { it.isNotBlank() }?.let(result::add)
        start = end
    }
    return result
}
