package org.koitharu.kotatsu.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.CF_STATE_JS
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
open class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {

	protected open val isHiddenAutoResolveActivity = false

	private var pendingResult = RESULT_CANCELED
	private val isAutoResolve: Boolean by lazy { intent?.getBooleanExtra(EXTRA_AUTO_RESOLVE, false) == true }
	private val autoRecreateCount: Int by lazy {
		intent?.getIntExtra(EXTRA_AUTO_RECREATE_COUNT, 0) ?: 0
	}
	private var resultNotified = false
	private var recreateRequested = false
	private var clearanceAtLaunch: String? = null
	private var clearanceUpdateObservedAt = 0L
	private var resolveJob: Job? = null
	private var autoSolveJob: Job? = null
	private var isHiddenPresentation = false
	private var lastChallengeState: String? = null
	private var autoSolveAttempted = false

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		if (isHiddenAutoResolveActivity) {
			isHiddenPresentation = true
			// Keep the window focused and the WebView rendered. Turnstile treats a non-focused or
			// detached WebView differently; the translucent theme exposes the previous screen.
			viewBinding.appbar.isGone = true
			viewBinding.root.alpha = HIDDEN_RENDER_ALPHA
			window.setDimAmount(0f)
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
			(this as? CloudFlareHiddenActivity)?.let(captchaAutoResolveCoordinator::registerHiddenActivity)
		} else {
			setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		}
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		clearanceAtLaunch = CloudFlareHelper.getClearanceCookie(cookieJar, url)

		val needsInterception = shouldUseInterception(source, repository)
		Log.d(TAG, "Source: ${source.name}, needsInterception: $needsInterception")
		cfClient = if (needsInterception) {
			CloudFlareInterceptClient(cookieJar, this, adBlock, url)
		} else {
			CloudFlareClient(cookieJar, this, adBlock, url)
		}
		viewBinding.webView.webViewClient = cfClient

		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null || autoRecreateCount > 0) {
				if (isAutoResolve && autoRecreateCount == 0) {
					url.toHttpUrlOrNull()?.let {
						clearRejectedClearance(it)
						CookieManager.getInstance().flush()
						Log.d(TAG, "Removed rejected cf_clearance before automatic challenge")
					}
				}
				awaitChallengeViewport()
				onTitleChanged(getString(R.string.loading_), url)
				viewBinding.webView.loadUrl(url)
			}
		}
		// Cookie changes can occur between managed-challenge stages. Only a stable loaded-page DOM
		// completes the flow.
		resolveJob = lifecycleScope.launch { runResolveLoop() }
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		if (isHiddenPresentation) return false
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		setResult(pendingResult)
		if (isAutoResolve && !resultNotified) {
			resultNotified = true
			intent?.getStringExtra(AppRouter.KEY_SOURCE)?.let { sourceName ->
				captchaAutoResolveCoordinator.notifyResolveResult(
					MangaSource(sourceName),
					pendingResult == RESULT_OK,
				)
			}
		}
		super.finish()
	}

	override fun onDestroy() {
		resolveJob?.cancel()
		autoSolveJob?.cancel()
		(this as? CloudFlareHiddenActivity)?.let(captchaAutoResolveCoordinator::unregisterHiddenActivity)
		super.onDestroy()
	}

	fun shouldStayHiddenAndFocused(): Boolean =
		isAutoResolve && isHiddenPresentation && !isFinishing && !isDestroyed

	fun cancelAutomaticResolve() {
		if (!isAutoResolve || isFinishing || isDestroyed) return
		resolveJob?.cancel()
		autoSolveJob?.cancel()
		viewBinding.webView.stopLoading()
		finishAfterTransition()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	private suspend fun awaitChallengeViewport() {
		while (!viewBinding.webView.hasWindowFocus()) {
			delay(VIEWPORT_POLL_INTERVAL_MS)
		}
		suspendCancellableCoroutine { cont ->
			viewBinding.webView.doOnLayout {
				if (cont.isActive) cont.resume(Unit)
			}
		}
		suspendCancellableCoroutine { cont ->
			viewBinding.webView.postOnAnimation {
				if (cont.isActive) cont.resume(Unit)
			}
		}
	}

	override fun onPageLoaded() {
		viewBinding.progressBar.isInvisible = true
		maybeAutoSolveCloudflare()
	}

	private fun maybeAutoSolveCloudflare() {
		if (autoSolveAttempted || !settings.isCloudflareAutoSolverEnabled) return
		autoSolveAttempted = true
		autoSolveJob = lifecycleScope.launch {
			delay(CF_AUTO_SOLVE_DELAY_MS)
			runCatchingCancellable {
				// This dispatches directly to the focused, rendered WebView, so it can also run while
				// the containing window ignores physical touches.
				CloudflareSolver.solve(viewBinding.webView)
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
	}

	override fun onLoopDetected() = Unit

	override fun onCheckPassed() {
		if (isAutoResolve && autoRecreateCount < MAX_AUTO_RECREATE_COUNT) {
			markClearanceUpdateObserved()
		}
	}

	private suspend fun runResolveLoop() {
		val retryAt = System.currentTimeMillis() + if (autoRecreateCount < MAX_AUTO_RECREATE_COUNT) {
			AUTO_RETRY_DELAY_MS
		} else {
			MANUAL_FALLBACK_DELAY_MS
		}
		var consecutivePasses = 0
		val requiredStablePasses = if (isHiddenAutoResolveActivity && autoRecreateCount > 0) {
			HIDDEN_RECREATED_STABLE_PASSES
		} else {
			REQUIRED_STABLE_PASSES
		}
		while (true) {
			delay(RESOLVE_POLL_INTERVAL_MS)
			if (isAutoResolve && autoRecreateCount < MAX_AUTO_RECREATE_COUNT) {
				val clearance = intent.dataString?.let {
					CloudFlareHelper.getClearanceCookie(cookieJar, it)
				}
				if (clearance != null && clearance != clearanceAtLaunch) {
					markClearanceUpdateObserved()
				}
			}
			val challengeState = probeChallengeState()
			if (challengeState != lastChallengeState) {
				lastChallengeState = challengeState
				Log.d(
					TAG,
					"Challenge state: $challengeState, url=${viewBinding.webView.url}, " +
						"title=${viewBinding.webView.title}",
				)
			}
			if (challengeState == CF_STATE_OK) {
				consecutivePasses++
				if (consecutivePasses >= requiredStablePasses) {
					finishSuccess()
					return
				}
			} else {
				consecutivePasses = 0
				val now = System.currentTimeMillis()
				if (
					clearanceUpdateObservedAt != 0L &&
					now - clearanceUpdateObservedAt >= CLEARANCE_RECREATE_GRACE_MS
				) {
					requestAutoRecreate("clearance updated but challenge remained")
					return
				}
				if (
					isAutoResolve &&
					autoRecreateCount < MAX_AUTO_RECREATE_COUNT &&
					now >= retryAt
				) {
					requestAutoRecreate("profile warm-up timeout")
					return
				}
				if (
					isAutoResolve &&
					autoRecreateCount >= MAX_AUTO_RECREATE_COUNT &&
					isHiddenPresentation &&
					now >= retryAt
				) {
					revealForManualCompletion()
				}
			}
		}
	}

	private fun markClearanceUpdateObserved() {
		if (clearanceUpdateObservedAt == 0L) {
			clearanceUpdateObservedAt = System.currentTimeMillis()
		}
	}

	private fun requestAutoRecreate(reason: String) {
		if (recreateRequested || autoRecreateCount >= MAX_AUTO_RECREATE_COUNT) return
		recreateRequested = true
		resolveJob?.cancel()
		autoSolveJob?.cancel()
		CookieManager.getInstance().flush()
		Log.d(TAG, "Recreating automatic challenge after $reason with preserved browser profile")
		intent.putExtra(EXTRA_AUTO_RECREATE_COUNT, autoRecreateCount + 1)
		recreate()
	}

	private fun revealForManualCompletion() {
		if (!isHiddenPresentation) return
		isHiddenPresentation = false
		viewBinding.root.alpha = 1f
		viewBinding.appbar.isGone = false
		window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		invalidateOptionsMenu()
		Log.d(TAG, "Automatic challenge needs user interaction; revealing the existing solver")
	}

	private suspend fun probeChallengeState(): String = suspendCancellableCoroutine { cont ->
		viewBinding.webView.evaluateJavascript(CF_STATE_JS) { raw ->
			if (cont.isActive) {
				cont.resume(raw?.removeSurrounding("\"") ?: CF_STATE_WAIT)
			}
		}
	}

	private fun finishSuccess() {
		if (pendingResult == RESULT_OK) return
		pendingResult = RESULT_OK
		resolveJob?.cancel()
		autoSolveJob?.cancel()
		lifecycleScope.launch {
			CookieManager.getInstance().flush()
			intent?.getStringExtra(AppRouter.KEY_SOURCE)?.let { source ->
				runCatchingCancellable {
					captchaHandler.discard(MangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			resolveJob?.cancel()
			autoSolveJob?.cancel()
			autoSolveAttempted = false
			lastChallengeState = null
			viewBinding.webView.stopLoading()
			yield()
			cfClient.reset()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				clearanceAtLaunch = null
				clearanceUpdateObservedAt = 0L
				viewBinding.webView.loadUrl(targetUrl.toString())
				resolveJob = lifecycleScope.launch { runResolveLoop() }
			}
		}
	}

	private suspend fun clearRejectedClearance(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie -> cookie.name == CLEARANCE_COOKIE_NAME }
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie -> CloudFlareHelper.isCloudFlareCookie(cookie.name) }
	}

	private fun shouldUseInterception(source: MangaSource, repository: ParserMangaRepository?): Boolean {
		Log.d(TAG, "shouldUseInterception called for source: ${source.name}")
		if (repository !is ParserMangaRepository) return false
		val interceptKey = repository.getConfigKeys().filterIsInstance<ConfigKey.InterceptCloudflare>().firstOrNull()
		return interceptKey?.defaultValue == true
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == RESULT_OK
	}

	companion object {
		const val TAG = "CloudFlareActivity"
		const val EXTRA_AUTO_RESOLVE = "auto_resolve"
		private const val VIEWPORT_POLL_INTERVAL_MS = 16L
		private const val EXTRA_AUTO_RECREATE_COUNT = "auto_recreate_count"
		private const val RESOLVE_POLL_INTERVAL_MS = 800L
		private const val AUTO_RETRY_DELAY_MS = 6_000L
		private const val MANUAL_FALLBACK_DELAY_MS = 15_000L
		private const val CLEARANCE_RECREATE_GRACE_MS = 500L
		private const val REQUIRED_STABLE_PASSES = 3
		private const val MAX_AUTO_RECREATE_COUNT = 1
		private const val CLEARANCE_COOKIE_NAME = "cf_clearance"
		private const val HIDDEN_RECREATED_STABLE_PASSES = 1
		private const val HIDDEN_RENDER_ALPHA = 0.01f
		private const val CF_AUTO_SOLVE_DELAY_MS = 2_000L
		private const val CF_STATE_OK = "ok"
		private const val CF_STATE_WAIT = "wait"
	}
}
