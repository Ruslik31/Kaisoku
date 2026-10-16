package org.koitharu.kotatsu.reader.domain

import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

internal fun List<ReaderPage>.boundedPrefetchPages(fromIndex: Int, toIndex: Int): List<ReaderPage> {
    val start = fromIndex.coerceIn(0, size)
    val end = toIndex.coerceIn(start, size)
    return subList(start, end)
}

/** Select by chapter identity because appending pages can also trim the beginning of the snapshot. */
internal fun List<ReaderPage>.nextChapterPrefetchPages(currentChapterId: Long, limit: Int): List<ReaderPage> {
    if (limit <= 0) return emptyList()
    val boundary = indexOfLast { it.chapterId == currentChapterId }
    if (boundary < 0) return emptyList()
    val first = boundary + 1
    val nextChapterId = getOrNull(first)?.chapterId ?: return emptyList()
    var end = first
    while (end < size && end - first < limit && this[end].chapterId == nextChapterId) {
        end++
    }
    return subList(first, end)
}
