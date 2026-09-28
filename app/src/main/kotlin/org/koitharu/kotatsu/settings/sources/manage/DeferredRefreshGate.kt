package org.koitharu.kotatsu.settings.sources.manage

/** Coalesces refreshes while a UI interaction temporarily owns a mutable list. */
internal class DeferredRefreshGate {

	private val lock = Any()
	private var pauseCount = 0
	private var refreshPending = false

	fun pause() {
		synchronized(lock) {
			pauseCount++
		}
	}

	/** Returns true when a deferred refresh should run now. */
	fun resume(): Boolean = synchronized(lock) {
		if (pauseCount > 0) pauseCount--
		if (pauseCount == 0 && refreshPending) {
			refreshPending = false
			true
		} else {
			false
		}
	}

	/** Returns true when a refresh may run immediately, or records it for the last resume. */
	fun requestRefresh(): Boolean = synchronized(lock) {
		if (pauseCount > 0) {
			refreshPending = true
			false
		} else {
			true
		}
	}
}
