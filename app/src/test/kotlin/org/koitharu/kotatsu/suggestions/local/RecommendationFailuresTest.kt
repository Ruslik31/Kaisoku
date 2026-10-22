package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RecommendationFailuresTest {
    @Test fun failedSourceDoesNotCancelConcurrentSuccessfulDiscovery() = runTest {
        val failures = RecommendationFailures(null)
        var cooldowns = 0
        val bad = async {
            failures.capture("bad", "Broken source", onFailure = { cooldowns++ }) {
                delay(10)
                throw IOException("DNS unavailable")
            }
        }
        val good = async {
            failures.capture("good", "Working source") { delay(20); listOf("Fresh title") }
        }
        assertNull(bad.await())
        assertEquals(listOf("Fresh title"), good.await())
        assertEquals(1, cooldowns)
        val report = RecommendationFailures.summary(failures.encode()!!)
        assertTrue(report.contains("Broken source"))
        assertFalse(report.contains("Working source"))
    }

    @Test fun aLaterSourceFailureKeepsEarlierResultsAndContinuesOtherSources() = runTest {
        val failures = RecommendationFailures(null)
        val found = mutableListOf<String>()
        failures.capture("mixed", "Mixed source", recordSuccess = false) {
            failures.capture("mixed", "Mixed source") { listOf("Earlier result") }?.let(found::addAll)
            throw IOException("Filter metadata failed")
        }
        failures.capture("next", "Next source") { listOf("Later result") }?.let(found::addAll)
        assertEquals(listOf("Earlier result", "Later result"), found)
        assertTrue(RecommendationFailures.summary(failures.encode()!!).contains("Filter metadata failed"))
    }

    @Test fun allFailedSourcesAreStoredWithFullCausesInStableOrder() {
        val failures = RecommendationFailures(null)
        (8 downTo 1).forEach { index ->
            failures.failed("source$index", "Source $index", IOException("Short error $index\nJS frame",
                IllegalArgumentException("Original cause $index")))
        }
        val encoded = failures.encode()!!
        val restored = RecommendationFailures(encoded).encode()!!
        assertEquals(encoded, restored)
        val summary = RecommendationFailures.summary(restored)
        val full = RecommendationFailures.fullDetails(restored)
        (1..8).forEach { index ->
            assertTrue(summary.contains("Source $index\nShort error $index"))
            assertTrue(full.contains("Original cause $index"))
        }
        assertFalse(summary.contains("JS frame"))
        assertTrue(full.contains("JS frame"))
        assertTrue(summary.indexOf("Source 1") < summary.indexOf("Source 8"))
    }

    @Test fun skippedCooldownAndOtherSourceSuccessDoNotClearUnresolvedErrors() = runTest {
        val old = RecommendationFailures(null).apply { failed("bad", "Bad", IOException("Still offline")) }.encode()
        val failures = RecommendationFailures(old)
        // A source task that did not get an operation permit is not proof of recovery.
        failures.capture("bad", "Bad", recordSuccess = false) { Unit }
        failures.capture("good", "Good") { Unit }
        assertEquals(old, failures.encode())
        failures.capture("bad", "Bad") { Unit }
        assertNull(failures.encode())
    }

    @Test fun successFromSameRefreshDoesNotHideAnotherFailedOperation() = runTest {
        val failures = RecommendationFailures(null)
        failures.capture("source", "Source") { throw IOException("Failed operation") }
        failures.capture("source", "Source") { "Successful operation" }
        assertTrue(RecommendationFailures.summary(failures.encode()!!).contains("Failed operation"))
    }

    @Test fun disabledSourcesAndProvidersAreRemovedFromWarnings() {
        val failures = RecommendationFailures(null).apply {
            failed("enabled", "Enabled", IOException("Error"))
            failed("disabled", "Disabled", IOException("Error"))
            failed("public:ANILIST", "AniList", IOException("Error"))
        }
        failures.retainSources(setOf("enabled"))
        val summary = RecommendationFailures.summary(failures.encode()!!)
        assertTrue(summary.contains("Enabled"))
        assertFalse(summary.contains("Disabled"))
        assertFalse(summary.contains("AniList"))
    }

    @Test fun operationTimeoutIsRecordedAndOtherDiscoveryCanContinue() = runTest {
        val failures = RecommendationFailures(null)
        assertNull(failures.capture("slow", "Slow") { withTimeout(10) { delay(20); "Never" } })
        assertEquals("Result", failures.capture("good", "Good") { "Result" })
        assertTrue(RecommendationFailures.summary(failures.encode()!!).contains("Request timed out"))
    }

    @Test fun callerCancellationAndOuterTimeoutAreNeverRecordedAsSourceFailures() = runTest {
        val failures = RecommendationFailures(null)
        var cooldowns = 0
        try {
            failures.capture("source", "Source", onFailure = { cooldowns++ }) {
                throw CancellationException("Cancelled by user")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        try {
            withTimeout(10) { failures.capture("source", "Source", onFailure = { cooldowns++ }) { delay(20) } }
            fail("Outer timeout must propagate")
        } catch (_: CancellationException) { }
        assertNull(failures.encode())
        assertEquals(0, cooldowns)
    }

    @Test fun oldPlaintextAndMalformedCachedReportsRemainExactlyCopyable() {
        for (text in listOf("DESUME: DNS failure\n    at frame", "{broken report")) {
            assertEquals(text, RecommendationFailures.summary(text))
            assertEquals(text, RecommendationFailures.fullDetails(text))
            assertEquals(text, RecommendationFailures(text).encode())
        }
    }
}
