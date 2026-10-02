package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Mihon's first interceptor, also used as an insertion point by extension networking helpers. */
class UncaughtExceptionInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response = try {
		chain.proceed(chain.request())
	} catch (e: IOException) {
		throw e
	} catch (e: Exception) {
		throw IOException(e)
	}
}
