package org.koitharu.kotatsu.core.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process-wide cache of the manual per-source NSFW overrides stored in the `sources` table.
 *
 * [MangaSource.isNsfw] is a synchronous extension function with a large number of call sites,
 * so the overrides have to be readable without suspending. The cache is populated and kept in
 * sync by `NsfwOverridesLoader`, which observes the `sources` table.
 */
object NsfwSourceOverrides {

	@Volatile
	private var overrides: Map<String, Boolean> = emptyMap()

	val updates = MutableSharedFlow<Unit>(
		replay = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	/**
	 * @return `true`/`false` when the source was manually marked, `null` when it inherits
	 * its intrinsic rating.
	 */
	fun peek(sourceName: String): Boolean? = overrides[sourceName]

	fun replaceAll(value: Map<String, Boolean>) {
		if (overrides == value) {
			return
		}
		overrides = value
		updates.tryEmit(Unit)
	}
}
