package org.koitharu.kotatsu.reader.domain

/** Returns chapter IDs in the order used by reader navigation and adjacent-chapter preloading. */
internal fun <T> itemsInReadingOrder(items: List<T>, reversed: Boolean): List<T> =
	if (reversed) items.asReversed() else items
