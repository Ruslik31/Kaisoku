package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import org.jsoup.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.InputRequiredException
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SourceHealthMonitorTest {

	@Test
	fun sourceFailuresCount() {
		assertTrue(SourceHealthMonitor.isSourceSideError(SocketTimeoutException()))
		assertTrue(SourceHealthMonitor.isSourceSideError(UnknownHostException()))
		assertTrue(SourceHealthMonitor.isSourceSideError(ParseException("x", "https://a.b")))
		assertTrue(SourceHealthMonitor.isSourceSideError(HttpStatusException("x", 503, "https://a.b")))
		assertTrue(SourceHealthMonitor.isSourceSideError(IOException("wrapped", SocketTimeoutException())))
	}

	@Test
	fun userResolvableFailuresDoNotCount() {
		val source = UnknownMangaSource
		assertFalse(SourceHealthMonitor.isSourceSideError(CloudFlareProtectedException("https://a.b", source, Headers.headersOf())))
		assertFalse(SourceHealthMonitor.isSourceSideError(AuthRequiredException(source)))
		assertFalse(SourceHealthMonitor.isSourceSideError(InputRequiredException(source, "Password", "input.p")))
		assertFalse(SourceHealthMonitor.isSourceSideError(SSLHandshakeException("bad cert")))
		assertFalse(SourceHealthMonitor.isSourceSideError(CancellationException()))
		assertFalse(SourceHealthMonitor.isSourceSideError(HttpStatusException("x", 403, "https://a.b")))
		assertFalse(SourceHealthMonitor.isSourceSideError(IllegalStateException("bug")))
	}

	@Test
	fun chapterFailureFiresOnceUntilReset() {
		val monitor = SourceHealthMonitor()
		assertEquals(SourceHealthMonitor.Trigger.ERRORS, monitor.onChapterFailure(SocketTimeoutException(), isOnline = true))
		assertNull(monitor.onChapterFailure(SocketTimeoutException(), isOnline = true))
		assertNull(monitor.onSlowLoad(isOnline = true))
		monitor.reset()
		assertEquals(SourceHealthMonitor.Trigger.SLOW, monitor.onSlowLoad(isOnline = true))
	}

	@Test
	fun errorsMayFollowAnIgnoredSlowHint() {
		val monitor = SourceHealthMonitor()
		assertEquals(SourceHealthMonitor.Trigger.SLOW, monitor.onSlowLoad(isOnline = true))
		assertNull(monitor.onSlowLoad(isOnline = true))
		assertEquals(SourceHealthMonitor.Trigger.ERRORS, monitor.onChapterFailure(SocketTimeoutException(), isOnline = true))
	}

	@Test
	fun offlineNeverFires() {
		val monitor = SourceHealthMonitor()
		assertNull(monitor.onChapterFailure(SocketTimeoutException(), isOnline = false))
		assertNull(monitor.onSlowLoad(isOnline = false))
		repeat(5) { assertNull(monitor.onPageFailure(it.toLong(), SocketTimeoutException(), isOnline = false)) }
	}

	@Test
	fun pageFailuresNeedDistinctPagesWithinTheWindow() {
		var now = 0L
		val monitor = SourceHealthMonitor(pageFailureThreshold = 3, pageFailureWindowMs = 1000L, clock = { now })
		assertNull(monitor.onPageFailure(1, SocketTimeoutException(), isOnline = true))
		assertNull(monitor.onPageFailure(1, SocketTimeoutException(), isOnline = true))
		assertNull(monitor.onPageFailure(2, SocketTimeoutException(), isOnline = true))
		now = 2000L
		assertNull(monitor.onPageFailure(3, SocketTimeoutException(), isOnline = true))
		assertNull(monitor.onPageFailure(4, SocketTimeoutException(), isOnline = true))
		assertEquals(
			SourceHealthMonitor.Trigger.ERRORS,
			monitor.onPageFailure(5, SocketTimeoutException(), isOnline = true),
		)
	}
}
