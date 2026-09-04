package org.koitharu.kotatsu.sync.drive

import android.app.Notification
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GoogleDriveWorker(
	context: Context,
	params: WorkerParameters,
	private val repository: GoogleDriveSyncRepository,
	private val settings: SyncBackendSettings,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result = coroutineScope {
		if (settings.backend != SyncBackend.GOOGLE_DRIVE) return@coroutineScope Result.success()
		setForeground(getForegroundInfo())
		val progressJob = launch {
			repository.progress.collectLatest { progress ->
				if (
					Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
					ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
					PackageManager.PERMISSION_GRANTED
				) {
					NotificationManagerCompat.from(applicationContext)
						.notify(NOTIFICATION_ID, notification(progress))
				}
			}
		}
		try {
			when (val result = repository.sync()) {
				DriveSyncResult.Success -> Result.success()
				DriveSyncResult.SignInRequired -> Result.failure()
				is DriveSyncResult.Error -> if (result.retryable && runAttemptCount < 3) Result.retry() else Result.failure()
			}
		} finally {
			progressJob.cancel()
			NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
		}
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		createChannel()
		val notification = notification(repository.progress.value)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(NOTIFICATION_ID, notification)
		}
	}

	private fun notification(progress: DriveSyncProgress): Notification {
		val total = progress.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		val current = progress.transferred.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.setContentTitle(applicationContext.getString(R.string.google_drive_sync))
			.setContentText(applicationContext.getString(progress.stage.titleRes))
			.setSilent(true)
			.setOngoing(true)
			.setProgress(total, current, total <= 0)
			.build()
	}

	private fun createChannel() {
		NotificationManagerCompat.from(applicationContext).createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(applicationContext.getString(R.string.google_drive_sync))
				.build(),
		)
	}

	private val DriveSyncProgress.Stage.titleRes: Int
		get() = when (this) {
			DriveSyncProgress.Stage.IDLE -> R.string.sync
			DriveSyncProgress.Stage.AUTHORIZING -> R.string.drive_sync_authorizing
			DriveSyncProgress.Stage.DOWNLOADING -> R.string.drive_sync_downloading
			DriveSyncProgress.Stage.MERGING -> R.string.drive_sync_merging
			DriveSyncProgress.Stage.PREPARING -> R.string.drive_sync_preparing
			DriveSyncProgress.Stage.UPLOADING -> R.string.drive_sync_uploading
		}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
		private val settings: SyncBackendSettings,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			if (settings.backend != SyncBackend.GOOGLE_DRIVE || settings.intervalMinutes <= 0) {
				unschedule()
				return
			}
			val request = PeriodicWorkRequestBuilder<GoogleDriveWorker>(
				settings.intervalMinutes.coerceAtLeast(15).toLong(),
				TimeUnit.MINUTES,
			)
				.setConstraints(constraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(WORK_NAME).await()
			workManager.cancelUniqueWork(WORK_NOW_NAME).await()
		}

		override suspend fun isScheduled(): Boolean =
			workManager.awaitUniqueWorkInfoByName(WORK_NAME).any { !it.state.isFinished }

		suspend fun startNow() {
			val request = OneTimeWorkRequestBuilder<GoogleDriveWorker>()
				.setConstraints(constraints(requireBatteryNotLow = false))
				.build()
			workManager.enqueueUniqueWork(WORK_NOW_NAME, ExistingWorkPolicy.KEEP, request).await()
		}

		private fun constraints(requireBatteryNotLow: Boolean = true) = Constraints.Builder()
			.setRequiredNetworkType(if (settings.isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(requireBatteryNotLow)
			.build()
	}

	companion object {
		private const val WORK_NAME = "google-drive-sync"
		private const val WORK_NOW_NAME = "google-drive-sync-now"
		private const val CHANNEL_ID = "google_drive_sync"
		private const val NOTIFICATION_ID = 47
	}
}
