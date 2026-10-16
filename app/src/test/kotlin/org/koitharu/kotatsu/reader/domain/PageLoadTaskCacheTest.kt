package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PageLoadTaskCacheTest {

	@Test
	fun concurrentForegroundAndPrefetchRequestsCreateOnlyOneTask() {
		val cache = cache()
		val starts = AtomicInteger()
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(8)
		try {
			val requests = List(64) {
				executor.submit<Task> {
					check(start.await(5, TimeUnit.SECONDS))
					cache.getOrCreate(1L) {
						starts.incrementAndGet()
						Task()
					}
				}
			}
			start.countDown()
			val tasks = requests.map { it.get(5, TimeUnit.SECONDS) }
			assertEquals(1, starts.get())
			assertTrue(tasks.all { it === tasks.first() })
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun prefetchKeepsAnExistingVisibleLoad() {
		val cache = cache()
		val visible = cache.getOrCreate(1L) { Task() }
		val prefetched = cache.getOrCreate(1L) { error("Duplicate page request") }
		assertSame(visible, prefetched)
		assertFalse(visible.cancelled)
	}

	@Test
	fun forceReloadCancelsTheOldTaskAndPublishesItsReplacement() {
		val cache = cache()
		val old = cache.getOrCreate(1L) { Task() }
		val fresh = cache.getOrCreate(1L, force = true) { Task() }
		assertTrue(old.cancelled)
		assertNotSame(old, fresh)
		assertSame(fresh, cache.getOrCreate(1L) { error("Duplicate replacement") })
	}

	@Test
	fun failedCancelledAndExpiredLoadsCanBeRetried() {
		val cache = cache()
		val failed = cache.getOrCreate(1L) { Task(valid = false) }
		val retry = cache.getOrCreate(1L) { Task() }
		assertNotSame(failed, retry)
		retry.cancelled = true
		assertNotSame(retry, cache.getOrCreate(1L) { Task() })
	}

	@Test
	fun invalidationDoesNotReuseAnOldTaskOrCancelItOutsideTheLoaderLifecycle() {
		val cache = cache()
		val old = cache.getOrCreate(1L) { Task() }
		cache.clear()
		assertFalse(cache.contains(1L))
		assertFalse(old.cancelled)
		assertNotSame(old, cache.getOrCreate(1L) { Task() })
	}

	@Test
	fun differentPagesHaveIndependentTasks() {
		val cache = cache()
		assertNotSame(cache.getOrCreate(1L) { Task() }, cache.getOrCreate(2L) { Task() })
	}

	@Test
	fun failedTaskCreationLeavesThePageRetryable() {
		val cache = cache()
		val error = IllegalStateException("Task could not start")
		try {
			cache.getOrCreate(1L) { throw error }
			throw AssertionError("Expected task creation failure")
		} catch (actual: IllegalStateException) {
			assertSame(error, actual)
		}
		assertFalse(cache.contains(1L))
		cache.getOrCreate(1L) { Task() }
		assertTrue(cache.contains(1L))
	}

	private fun cache() = PageLoadTaskCache<Task>(
		isValid = { it.valid && !it.cancelled },
		cancel = { it.cancelled = true },
	)

	private class Task(var valid: Boolean = true, var cancelled: Boolean = false)
}
