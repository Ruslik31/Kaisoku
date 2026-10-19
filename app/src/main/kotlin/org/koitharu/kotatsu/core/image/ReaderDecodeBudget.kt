package org.koitharu.kotatsu.core.image

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Semaphore

/** Only the exceptional full-image reader path uses this budget. Native tiles keep their existing policy. */
internal class ReaderDecodeBudget(val capacity: Long) {

    private var used = 0L

    @Synchronized
    fun reserve(bytes: Long): Closeable {
        if (bytes <= 0L || bytes > capacity - used) {
            throw IOException("Reader compatibility memory budget exceeded")
        }
        used += bytes
        return object : Closeable {
            private var closed = false

            override fun close() = synchronized(this@ReaderDecodeBudget) {
                if (!closed) {
                    used -= bytes
                    closed = true
                }
            }
        }
    }
}

internal object ReaderDecodeLimits {

    const val MAX_BITMAP_BYTES = 16L * 1024 * 1024
    const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
    private const val MAX_DIMENSION = 32_768

    // Source pixels, native codec workspace and one full-size output/crop. Always budget four bytes per pixel.
    fun workingBytes(width: Int, height: Int, lowRam: Boolean): Long {
        val limit = if (lowRam) MAX_BITMAP_BYTES / 2 else MAX_BITMAP_BYTES
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            throw IOException("Invalid reader compatibility image dimensions")
        }
        val bytes = width.toLong() * height * 4
        if (bytes > limit) {
            throw IOException("Image exceeds reader compatibility pixel budget")
        }
        return bytes * 3
    }

    fun sampledDimension(size: Int, sampleSize: Int): Int {
        require(size > 0 && sampleSize > 0)
        return ((size.toLong() + sampleSize - 1) / sampleSize).toInt()
    }

    fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Reader compatibility decode interrupted")
        }
    }
}

internal object ReaderDecodeResources {

    val budget = ReaderDecodeBudget(minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4))
    private val allocationPermit = Semaphore(1, true)

    fun reserve(bytes: Long): Closeable {
        ReaderDecodeLimits.checkInterrupted()
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        if (bytes > available - 8L * 1024 * 1024) {
            throw IOException("Insufficient heap for reader compatibility decode")
        }
        return budget.reserve(bytes)
    }

    fun <T> withAllocationPermit(block: () -> T): T {
        allocationPermit.acquire()
        return try {
            ReaderDecodeLimits.checkInterrupted()
            block()
        } finally {
            allocationPermit.release()
        }
    }
}
