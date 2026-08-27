package org.koitharu.kotatsu

import android.app.Notification
import android.app.Notification.BigTextStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import android.os.strictmode.Violation
import androidx.annotation.RequiresApi
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.strictmode.FragmentStrictMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import kotlin.math.absoluteValue
import androidx.fragment.app.strictmode.Violation as FragmentViolation

@RequiresApi(Build.VERSION_CODES.P)
class StrictModeNotifier(
	private val context: Context,
) : StrictMode.OnVmViolationListener, StrictMode.OnThreadViolationListener, FragmentStrictMode.OnViolationListener {

	val executor = Dispatchers.Default.asExecutor()

	private val notificationManager by lazy {
		val nm = checkNotNull(context.getSystemService<NotificationManager>())
		val channel = NotificationChannel(
			CHANNEL_ID,
			context.getString(R.string.strict_mode),
			NotificationManager.IMPORTANCE_LOW,
		)
		nm.createNotificationChannel(channel)
		nm
	}

	private val lastShown = HashMap<String, Long>()

	init {
		runCatching {
			context.getSystemService<NotificationManager>()?.cancelAll()
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	override fun onVmViolation(v: Violation) = showNotification(v)

	override fun onThreadViolation(v: Violation) = showNotification(v)

	override fun onViolation(violation: FragmentViolation) = showNotification(violation)

	private fun showNotification(violation: Throwable) {
		if (violation.isFrameworkOnly()) {
			return
		}
		val now = SystemClock.elapsedRealtime()
		val key = violation.message + "\n" + (violation.stackTrace.firstOrNull()?.toString().orEmpty())
		synchronized(lastShown) {
			val last = lastShown[key]
			if (last != null && now - last < THROTTLE_MS) {
				return
			}
			lastShown[key] = now
			if (lastShown.size > MAX_KEYS) {
				lastShown.clear()
			}
		}
		runCatching {
			Notification.Builder(context, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_bug)
				.setContentTitle(context.getString(R.string.strict_mode))
				.setContentText(violation.message)
				.setStyle(
					BigTextStyle()
						.setBigContentTitle(context.getString(R.string.strict_mode))
						.setSummaryText(violation.message)
						.bigText(violation.stackTraceToString()),
				).setShowWhen(true)
				.setContentIntent(
					PendingIntentCompat.getActivity(
						context,
						violation.hashCode(),
						ShareHelper(context).getShareTextIntent(violation.stackTraceToString()),
						0,
						false,
					),
				)
				.setAutoCancel(true)
				.setGroup(CHANNEL_ID)
				.build()
				.let { notificationManager.notify(CHANNEL_ID, violation.hashCode().absoluteValue, it) }
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	private fun Throwable.isFrameworkOnly(): Boolean {
		val frames = stackTrace
		val ioIndex = frames.indexOfFirst {
			it.className.startsWith("java.io.FileOutput") || it.className.startsWith("java.io.FileInput")
		}
		if (ioIndex in 0 until frames.size - 1) {
			val culprit = frames[ioIndex + 1].className
			return !(culprit.startsWith("org.koitharu.") ||
				culprit.startsWith("okhttp3.") ||
				culprit.startsWith("okio.") ||
				culprit.startsWith("androidx.") ||
				culprit.startsWith("kotlinx.") ||
				culprit.startsWith("coil3."))
		}
		return frames.none { it.className.startsWith("org.koitharu.kotatsu.") }
	}

	private companion object {

		const val CHANNEL_ID = "strict_mode"
		const val THROTTLE_MS = 2_000L
		const val MAX_KEYS = 64
	}
}
