package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Share a refresh, retaining it only while at least one caller still needs it. */
internal class RecommendationJobs(private val scope: CoroutineScope) {
    private class Entry(val generation: String, val work: Deferred<Unit>, var readers: Int = 0)
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun run(key: String, generation: String, block: suspend () -> Unit) {
        val entry = lock.withLock {
            (entries[key]?.takeIf { it.generation == generation && !it.work.isCompleted && !it.work.isCancelled }
                ?: Entry(generation, scope.async(start = CoroutineStart.LAZY) { block() }).also {
                    entries.remove(key)?.work?.cancel()
                    entries[key] = it
                }).also { it.readers++ }
        }.also { it.work.start() }
        try {
            entry.work.await()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (--entry.readers == 0) {
                        if (entries[key] === entry) entries.remove(key)
                        entry.work.cancel()
                    }
                }
            }
        }
    }

    suspend fun invalidate(generation: String) = lock.withLock {
        entries.entries.removeAll { (_, entry) ->
            (entry.generation != generation).also { if (it) entry.work.cancel() }
        }
    }
}

internal class RecommendationBudget(private val limit: Int) {
    private val used = AtomicInteger()
    fun claim(): Boolean {
        while (true) {
            val current = used.get()
            if (current >= limit) return false
            if (used.compareAndSet(current, current + 1)) return true
        }
    }
}

/** Shared by automatic discovery and user-confirmed public-provider lookups. */
internal object RecommendationOperations {
    private val permits = Semaphore(2)
    suspend fun <T> run(block: suspend () -> T): T = permits.withPermit { block() }
}
