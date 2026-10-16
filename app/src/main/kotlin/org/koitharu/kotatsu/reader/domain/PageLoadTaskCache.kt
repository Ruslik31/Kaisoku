package org.koitharu.kotatsu.reader.domain

import androidx.collection.LongSparseArray

/** Keep lookup and task creation atomic across visible-page loads and prefetch. */
internal class PageLoadTaskCache<T : Any>(
	private val isValid: (T) -> Boolean,
	private val cancel: (T) -> Unit,
) {

	private val tasks = LongSparseArray<T>()

	@Synchronized
	fun contains(id: Long): Boolean = tasks.containsKey(id)

	@Synchronized
	fun getOrCreate(id: Long, force: Boolean = false, create: () -> T): T {
		val previous = tasks[id]
		if (!force && previous != null && isValid(previous)) return previous
		if (force && previous != null) cancel(previous)
		return create().also { tasks.put(id, it) }
	}

	@Synchronized
	fun clear() {
		tasks.clear()
	}
}
