package org.koitharu.kotatsu.reader.domain

/** Returns chapter IDs in the order used by reader navigation and adjacent-chapter preloading. */
internal fun <T> itemsInReadingOrder(items: List<T>, reversed: Boolean): List<T> =
	if (reversed) items.asReversed() else items

/** Map a valid source index without turning a missing chapter into a completed one. */
internal fun readingOrderIndex(index: Int, count: Int, reversed: Boolean): Int =
	if (reversed && index in 0 until count) count - index - 1 else index
