package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/** One queue for all workers, including chapters admitted before a finite limit was selected. */
internal class DownloadChapterQueue(private val configuredLimit: () -> Int) {

    private val lock = Any()
    private val waiting = ArrayDeque<Waiter>()
    private var active = 0

    suspend fun acquire(): Closeable {
        val waiter = Waiter()
        val admitted = synchronized(lock) {
            waiting.addLast(waiter)
            admitWaiting()
        }
        admitted.forEach { it.ready.complete(Unit) }
        try {
            waiter.ready.await()
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            release(waiter)
            throw e
        }
        return Closeable { release(waiter) }
    }

    fun onLimitChanged() {
        val admitted = synchronized(lock) {
            admitWaiting()
        }
        admitted.forEach { it.ready.complete(Unit) }
    }

    private fun release(waiter: Waiter) {
        val admitted = synchronized(lock) {
            when (waiter.state) {
                State.WAITING -> waiting.remove(waiter)
                State.ACTIVE -> active--
                State.RELEASED -> return
            }
            waiter.state = State.RELEASED
            admitWaiting()
        }
        admitted.forEach { it.ready.complete(Unit) }
    }

    // Called under lock. Complete deferreds outside the lock, as an unconfined continuation may run immediately.
    private fun admitWaiting(): List<Waiter> = buildList {
        val limit = configuredLimit().also { require(it >= 0) }
        while (waiting.isNotEmpty() && (limit == 0 || active < limit)) {
            val waiter = waiting.removeFirst()
            waiter.state = State.ACTIVE
            active++
            add(waiter)
        }
    }

    private class Waiter {
        val ready = CompletableDeferred<Unit>()
        var state = State.WAITING
    }

    private enum class State {
        WAITING, ACTIVE, RELEASED,
    }
}
