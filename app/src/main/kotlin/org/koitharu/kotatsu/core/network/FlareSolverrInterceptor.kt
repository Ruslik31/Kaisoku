package org.koitharu.kotatsu.core.network

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.edit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Optional, opt-in Cloudflare bypass via an external **FlareSolverr** server (Beta).
 *
 * FlareSolverr (https://github.com/FlareSolverr/FlareSolverr) runs a real headless Chromium on the
 * user's own desktop/LAN and returns the clearance cookies plus the user-agent they are bound to.
 * This interceptor sits *outside* [CloudFlareInterceptor]: when that inner interceptor throws a
 * [CloudFlareProtectedException], we ask FlareSolverr to solve the challenge, drop the resulting
 * cookies into the WebView [CookieManager] (which backs the OkHttp cookie jar) and retry the request
 * with FlareSolverr's user-agent. If FlareSolverr is disabled, unreachable, or fails, the original
 * exception is rethrown so the on-device WebView auto-solver still runs as a fallback.
 *
 * Cloudflare binds cf_clearance to the exact user-agent that solved it, so the resolved UA is
 * remembered per-host and re-applied to every later request for that host (see
 * [applyStoredUserAgent]); otherwise [CommonHeadersInterceptor] would stamp the device UA back on
 * and the very next request would re-trigger the challenge.
 */
class FlareSolverrInterceptor(
	private val settings: AppSettings,
	context: Context,
) : Interceptor {

	private val uaStore = context.applicationContext
		.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val locks = ConcurrentHashMap<String, Any>()
	private val lastSolvedAt = ConcurrentHashMap<String, Long>()

	private val client by lazy {
		OkHttpClient.Builder()
			.retryOnConnectionFailure(true)
			.build()
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		if (!settings.isFlareSolverrEnabled) {
			return chain.proceed(chain.request())
		}
		val request = applyStoredUserAgent(chain.request())
		return try {
			chain.proceed(request)
		} catch (e: CloudFlareProtectedException) {
			resolveAndRetry(chain, request, e)
		}
	}

	private fun resolveAndRetry(
		chain: Interceptor.Chain,
		request: Request,
		exception: CloudFlareProtectedException,
	): Response {
		val host = request.url.host
		val lock = locks.getOrPut(host) { Any() }
		synchronized(lock) {
			// Another thread may have solved this host while we waited on the lock.
			val solvedAt = lastSolvedAt[host]
			if (solvedAt == null || System.currentTimeMillis() - solvedAt > RESOLVE_COOLDOWN_MS) {
				val userAgent = runCatching {
					solveWithFlareSolverr(request.url.toString())
				}.getOrElse { e ->
					e.printStackTraceDebug()
					throw exception // fall back to the on-device WebView auto-solver
				}
				storeUserAgent(host, userAgent)
				lastSolvedAt[host] = System.currentTimeMillis()
			}
		}
		// Retry outside the catch: a second failure propagates to the WebView fallback.
		return chain.proceed(applyStoredUserAgent(request))
	}

	/**
	 * Calls FlareSolverr, writes the returned cookies into [CookieManager] and returns the
	 * user-agent the challenge was solved with. Throws on any transport/protocol error.
	 */
	private fun solveWithFlareSolverr(targetUrl: String): String {
		val payload = JSONObject()
			.put("cmd", "request.get")
			.put("url", targetUrl)
			.put("maxTimeout", settings.flareSolverrTimeoutMs)
			.put("returnOnlyCookies", true)
			.toString()
		val timeout = (settings.flareSolverrTimeoutMs + EXTRA_TIMEOUT_MS).toLong()
		val response = client.newBuilder()
			.connectTimeout(timeout, TimeUnit.MILLISECONDS)
			.readTimeout(timeout, TimeUnit.MILLISECONDS)
			.writeTimeout(timeout, TimeUnit.MILLISECONDS)
			.build()
			.newCall(
				Request.Builder()
					.url(settings.flareSolverrUrl)
					.post(payload.toRequestBody(JSON_MEDIA_TYPE))
					.build(),
			)
			.execute()
		response.use {
			val body = it.body.string()
			if (!it.isSuccessful || body.isEmpty()) {
				throw IOException("FlareSolverr HTTP ${it.code}")
			}
			val root = JSONObject(body)
			if (!root.optString("status").equals("ok", ignoreCase = true)) {
				val message = root.optString("message").ifEmpty { "challenge not solved" }
				throw IOException("FlareSolverr: $message")
			}
			val solution = root.getJSONObject("solution")
			val status = solution.optInt("status", 0)
			if (status !in 200..299) {
				throw IOException("FlareSolverr solution status $status")
			}
			applyCookies(solution.optJSONArray("cookies"))
			return solution.getString("userAgent")
		}
	}

	private fun applyCookies(cookies: JSONArray?) {
		if (cookies == null || cookies.length() == 0) {
			return
		}
		val cookieManager = CookieManager.getInstance()
		for (i in 0 until cookies.length()) {
			val cookie = cookies.optJSONObject(i) ?: continue
			val domain = cookie.optString("domain").removePrefix(".")
			if (domain.isEmpty()) continue
			cookieManager.setCookie("https://$domain", buildCookieString(cookie, domain))
		}
		runCatching { cookieManager.flush() }
	}

	private fun buildCookieString(cookie: JSONObject, domain: String): String {
		val path = cookie.optString("path", "/").ifEmpty { "/" }
		return buildString {
			append(cookie.optString("name")).append('=').append(cookie.optString("value"))
			append("; Domain=").append(domain)
			append("; Path=").append(path)
			if (cookie.optBoolean("httpOnly")) append("; HttpOnly")
			if (cookie.optBoolean("secure")) append("; Secure")
		}
	}

	private fun applyStoredUserAgent(request: Request): Request {
		val userAgent = uaStore.getString(request.url.host, null) ?: return request
		return request.newBuilder()
			.header(CommonHeaders.USER_AGENT, userAgent)
			.build()
	}

	private fun storeUserAgent(host: String, userAgent: String) {
		uaStore.edit { putString(host, userAgent) }
	}

	private companion object {

		const val PREFS_NAME = "flaresolverr_ua"
		const val EXTRA_TIMEOUT_MS = 15_000
		const val RESOLVE_COOLDOWN_MS = 15_000L
		val JSON_MEDIA_TYPE = "application/json".toMediaType()
	}
}
