package org.koitharu.kotatsu.core.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Process-wide synchronous cache of manual per-source NSFW overrides. */
object NsfwSourceOverrides {

	@Volatile
	private var overrides: Map<String, Boolean> = emptyMap()

	val updates = MutableSharedFlow<Unit>(
		replay = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	fun peek(sourceName: String): Boolean? = overrides[sourceName]

	fun replaceAll(value: Map<String, Boolean>) {
		if (overrides == value) {
			return
		}
		overrides = value
		updates.tryEmit(Unit)
	}
}
