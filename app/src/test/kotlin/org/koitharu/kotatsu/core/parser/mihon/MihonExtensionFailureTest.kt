package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MihonExtensionFailureTest {

    private val request = Request.Builder().url("https://extension.invalid/catalog").build()

    @Test
    fun noSuchMethodErrorsBecomeSourceFailures() {
        val cause = NoSuchMethodError("kotlinx.coroutines.BuildersKt.runBlockingK\$default")
        assertSame(cause, sourceFailure(cause).cause)
    }

    @Test
    fun missingClassesBecomeSourceFailures() {
        val cause = NoClassDefFoundError("missing/extension/runtime")
        assertSame(cause, sourceFailure(cause).cause)
    }

    @Test
    fun initializationErrorsBecomeSourceFailures() {
        val cause = ExceptionInInitializerError(IllegalStateException("extension initialization"))
        assertSame(cause, sourceFailure(cause).cause)
    }

    @Test
    fun linkageFailuresReachAsynchronousCallbacksAndTheClientCanBeReused() {
        val cause = NoSuchMethodError("kotlinx.coroutines.BuildersKt.runBlockingK\$default")
        val attempts = AtomicInteger()
        val callbacks = AtomicInteger()
        val failure = AtomicReference<IOException>()
        val finished = CountDownLatch(1)
        val client = MihonNetworkHelper(OkHttpClient()) { "Fixture agent" }.client.newBuilder()
            .addInterceptor { chain ->
                if (attempts.getAndIncrement() == 0) throw cause
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("catalog".toResponseBody())
                    .build()
            }.build()
        try {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failure.set(e)
                    callbacks.incrementAndGet()
                    finished.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    callbacks.incrementAndGet()
                    finished.countDown()
                }
            })
            assertTrue("The linkage error must reach onFailure", finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
            assertSame(cause, failure.get()?.cause)
            client.newCall(request).execute().use { assertEquals("catalog", it.body.string()) }
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun existingIoExceptionsKeepTheirIdentity() {
        val cause = IOException("source fixture")
        assertSame(cause, sourceFailure(cause))
    }

    @Test
    fun ordinaryExceptionsKeepTheirCause() {
        val cause = IllegalArgumentException("source fixture")
        assertSame(cause, sourceFailure(cause).cause)
    }

    @Test
    fun fatalVmAndAssertionErrorsAreNotConvertedToSourceFailures() {
        for (cause in listOf(OutOfMemoryError("fixture"), StackOverflowError("fixture"), AssertionError("fixture"))) {
            val client = failingClient(cause)
            assertSame(cause, assertThrows(Error::class.java) { client.newCall(request).execute() })
        }
    }

    private fun sourceFailure(cause: Throwable): IOException = assertThrows(IOException::class.java) {
        failingClient(cause).newCall(request).execute()
    }

    private fun failingClient(cause: Throwable): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor { throw cause }
        .build()
}
