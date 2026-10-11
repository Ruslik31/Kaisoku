package org.koitharu.kotatsu.suggestions.local

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

/** Host measurement, not an Android frame-time assertion. Results go into the local validation log. */
class RecommendationPerformanceTest {
    @Test fun boundedRankingFixtureRecordsTimingAndMemoryWithoutMutatingInputs() {
        val now = 1_900_000_000_000L
        val candidates = (1..500).map { Recommendation(RecommendationTarget.SourceTitle(title(it.toLong()))) }
        val taste = (501..1500).map { TasteTitle(title(it.toLong()), it % 3 == 0, now - it * 86400000L, it * 60000L) }
        fun rank() = RecommendationRanker.rank(candidates, taste, RecommendationRequest.Explore, 50, now)
        repeat(2) { rank() }
        val timings = ArrayList<Long>()
        val allocations = ArrayList<Long>()
        val allocationCounter = allocationCounter()
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        var largestHeap = before
        repeat(3) {
            val allocatedBefore = allocationCounter?.invoke()
            val started = System.nanoTime()
            val result = rank()
            timings.add(System.nanoTime() - started)
            if (allocatedBefore != null) allocations.add(allocationCounter!!.invoke() - allocatedBefore)
            largestHeap = maxOf(largestHeap, runtime.totalMemory() - runtime.freeMemory())
            assertEquals(160, result.size)
            assertTrue(result.all { it.score.isFinite() })
        }
        System.gc()
        val retained = (runtime.totalMemory() - runtime.freeMemory() - before).coerceAtLeast(0)
        println("LOCAL_RECOMMENDATION_BENCHMARK {\"candidates\":500,\"taste_titles\":1000," +
            "\"median_ms\":${timings.sorted()[1] / 1000000.0},\"sampled_heap_delta_bytes\":${(largestHeap - before).coerceAtLeast(0)}," +
            "\"allocated_bytes_per_run\":${allocations.sorted().getOrNull(1)}," +
            "\"retained_heap_delta_bytes\":$retained,\"runtime\":\"host JVM\"}")
        assertTrue(candidates.all { it.manga.chapters == null })
        assertEquals(1000, taste.size)
    }

    private fun allocationCounter(): (() -> Long)? = runCatching {
        // Reflection keeps JDK management types out of the Android compilation classpath.
        val service = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val contract = Class.forName("com.sun.management.ThreadMXBean")
        contract.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
            .invoke(service, true)
        val method = contract.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val read = { (method.invoke(service, Thread.currentThread().id) as Long) }
        read
    }.getOrNull()

    private fun title(id: Long): Manga {
        val name = when (id.toInt() % 3) {
            0 -> "我的狐仙旅途 $id"
            1 -> "حكاية الليل $id"
            else -> "Moon voyage $id"
        }
        return Manga(id, name, setOf("Alias voyage $id"), "/$id", "https://fixture.invalid/$id", -1f,
            null, null, (1..5).mapTo(linkedSetOf()) {
                MangaTag("Genre ${(id + it) % 20}", "${(id + it) % 20}", TestMangaSource)
            }, null, setOf("Author ${id % 100}"), source = TestMangaSource)
    }
}
