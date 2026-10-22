package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spaces chapter starts without reserving another start when a waiting worker is cancelled. */
internal class DownloadChapterStartGate(private val now: () -> Long) {

    private val mutex = Mutex()
    private var lastStart: Long? = null

    suspend fun awaitStart(intervalMillis: Long) {
        if (intervalMillis <= 0L) {
            return
        }
        mutex.withLock {
            lastStart?.let { previous ->
                delay((previous + intervalMillis - now()).coerceAtLeast(0L))
            }
            currentCoroutineContext().ensureActive()
            lastStart = now()
        }
    }
}
