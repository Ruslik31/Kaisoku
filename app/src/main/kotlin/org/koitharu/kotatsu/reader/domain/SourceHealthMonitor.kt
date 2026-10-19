package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CancellationException
import okhttp3.internal.http2.StreamResetException
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.EmptyMangaException
import org.koitharu.kotatsu.core.exceptions.InputRequiredException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.exceptions.ProxyConfigException
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.details.ui.pager.EmptyMangaReason
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Decides when the reader should suggest moving a title to another source. Purely observational:
 * it never blocks, retries or changes reader state. Each trigger fires at most once until [reset];
 * a slow-load hint never follows an error suggestion, but errors may follow an ignored slow hint.
 */
class SourceHealthMonitor(
	private val pageFailureThreshold: Int = PAGE_FAILURE_THRESHOLD,
	private val pageFailureWindowMs: Long = PAGE_FAILURE_WINDOW_MS,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	enum class Trigger { ERRORS, SLOW }

	private val pageFailures = LinkedHashMap<Long, Long>()
	private var firedErrors = false
	private var firedSlow = false

	@Synchronized
	fun onChapterFailure(error: Throwable, isOnline: Boolean): Trigger? =
		if (isOnline && isSourceSideError(error)) fireErrors() else null

	@Synchronized
	fun onPageFailure(pageId: Long, error: Throwable, isOnline: Boolean): Trigger? {
		if (!isOnline || !isSourceSideError(error)) {
			return null
		}
		val now = clock()
		pageFailures.entries.removeAll { now - it.value > pageFailureWindowMs }
		pageFailures[pageId] = now
		return if (pageFailures.size >= pageFailureThreshold) fireErrors() else null
	}

	@Synchronized
	fun onSlowLoad(isOnline: Boolean): Trigger? {
		if (!isOnline || firedSlow || firedErrors) return null
		firedSlow = true
		return Trigger.SLOW
	}

	@Synchronized
	fun reset() {
		firedErrors = false
		firedSlow = false
		pageFailures.clear()
	}

	private fun fireErrors(): Trigger? {
		if (firedErrors) return null
		firedErrors = true
		pageFailures.clear()
		return Trigger.ERRORS
	}

	companion object {

		const val PAGE_FAILURE_THRESHOLD = 3
		const val PAGE_FAILURE_WINDOW_MS = 120_000L
		const val SLOW_LOAD_TIMEOUT_MS = 20_000L

		/**
		 * True for failures that point at the source itself. Errors the user can resolve in place
		 * (captcha, sign-in, plugin input, proxy, certificate) do not count.
		 */
		fun isSourceSideError(error: Throwable): Boolean {
			val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
			if (chain.any { it.isUserResolvable() }) {
				return false
			}
			return chain.any { it.isSourceFailure() }
		}

		private fun Throwable.isUserResolvable(): Boolean = this is CancellationException ||
			this is CloudFlareProtectedException ||
			this is AuthRequiredException ||
			this is InteractiveActionRequiredException ||
			this is InputRequiredException ||
			this is ProxyConfigException ||
			this is SSLException ||
			this is EmptyMangaException && reason == EmptyMangaReason.RESTRICTED

		private fun Throwable.isSourceFailure(): Boolean = when (this) {
			is SocketTimeoutException,
			is UnknownHostException,
			is StreamResetException,
			is SocketException,
			is TooManyRequestExceptions,
			is ParseException,
			is ContentUnavailableException,
			is UnsupportedSourceException -> true

			is HttpStatusException -> statusCode == 404 || statusCode == 429 || statusCode >= 500
			is EmptyMangaException -> reason == EmptyMangaReason.NO_CHAPTERS
			is IOException -> true
			else -> false
		}
	}
}
