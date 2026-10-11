package org.koitharu.kotatsu.core.parser.mihon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import okio.Sink
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class MihonRuntimeDependenciesTest {

    @Test
    fun okhttpZstdDecoderIsAvailableToHostedExtensions() {
        val bridge = Class.forName("com.squareup.zstd.okio.OkioZstd")
        assertEquals(Source::class.java, bridge.getMethod("zstdDecompress", Source::class.java).returnType)
        assertEquals(Sink::class.java, bridge.getMethod("zstdCompress", Sink::class.java).returnType)
        assertNotNull(Class.forName("okhttp3.zstd.Zstd"))
    }

    @Test
    fun newAndLegacyBlockingEntryPointsSupportSuspendingBlocks() {
        val block: suspend CoroutineScope.() -> String = {
            async {
                delay(1)
                "extension result"
            }.await()
        }
        for (name in blockingEntryPoints) {
            assertEquals("extension result", invokeBlocking(name, null, block))
        }
    }

    @Test
    fun blockingEntryPointsPreserveExplicitContext() {
        val block: suspend CoroutineScope.() -> String = {
            currentCoroutineContext()[CoroutineName]?.name.orEmpty()
        }
        for (name in blockingEntryPoints) {
            assertEquals("source fixture", invokeBlocking(name, CoroutineName("source fixture"), block))
        }
    }

    @Test
    fun blockingEntryPointsKeepOriginalFailure() {
        val failure = IllegalStateException("extension fixture failure")
        val block: suspend CoroutineScope.() -> String = { throw failure }
        for (name in blockingEntryPoints) {
            val error = assertThrows(InvocationTargetException::class.java) {
                invokeBlocking(name, null, block)
            }
            assertSame(failure, error.cause)
        }
    }

    @Test
    fun blockingEntryPointsHonorCancelledContexts() {
        val job = Job().apply { cancel() }
        var entered = false
        val block: suspend CoroutineScope.() -> String = { entered = true; "unexpected" }
        for (name in blockingEntryPoints) {
            val error = assertThrows(InvocationTargetException::class.java) {
                invokeBlocking(name, job, block)
            }
            assertTrue(error.cause is CancellationException)
            assertFalse(entered)
        }
    }

    // Match calls from separately compiled extensions, including Kotlin default-argument bridges.
    private fun invokeBlocking(
        name: String,
        context: CoroutineContext?,
        block: suspend CoroutineScope.() -> String,
    ): Any? {
        val owner = Class.forName("kotlinx.coroutines.BuildersKt")
        return if (name.endsWith("\$default")) {
            owner.getMethod(
                name,
                CoroutineContext::class.java,
                Function2::class.java,
                Integer.TYPE,
                Any::class.java,
            ).invoke(null, context, block, if (context == null) 1 else 0, null)
        } else {
            owner.getMethod(name, CoroutineContext::class.java, Function2::class.java)
                .invoke(null, context ?: EmptyCoroutineContext, block)
        }
    }

    private val blockingEntryPoints = listOf(
        "runBlocking",
        "runBlocking\$default",
        "runBlockingK",
        "runBlockingK\$default",
    )
}
