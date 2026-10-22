package org.koitharu.kotatsu.core.diagnostics

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response

/** Never reads a URL, header, body or error message; disabled path only forwards the request. */
internal class DiagnosticInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!LocalDiagnostics.enabled) return chain.proceed(chain.request())
        val started = SystemClock.elapsedRealtime()
        try {
            val response = chain.proceed(chain.request())
            LocalDiagnostics.record(DiagnosticEvent.HTTP, status = response.code, duration = SystemClock.elapsedRealtime() - started)
            return response
        } catch (e: Exception) {
            LocalDiagnostics.error(e)
            throw e
        }
    }
}
