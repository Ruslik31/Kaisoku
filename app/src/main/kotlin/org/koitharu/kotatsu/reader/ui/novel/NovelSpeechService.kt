package org.koitharu.kotatsu.reader.ui.novel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter

/** Media controls adapted from DropSauce's ReaderTtsService (GPL-3.0). No engine/model dependency. */
@AndroidEntryPoint
internal class NovelSpeechService : LifecycleService() {
    @Inject lateinit var speech: NovelSpeechController
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private var started = false
    private var owner: Long? = null
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) speech.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.novel_read_aloud), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        session = MediaSession(this, "NovelSpeech").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = speech.resume()
                override fun onPause() = speech.pause()
                override fun onStop() = speech.stop()
                override fun onSkipToNext() = speech.skipChapter(1)
                override fun onSkipToPrevious() = speech.skipChapter(-1)
            })
            isActive = true
        }
        lifecycleScope.launch {
            speech.state.collect { state ->
                if (!state.isActive) {
                    if (started) {
                        ServiceCompat.stopForeground(this@NovelSpeechService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        started = false
                        state.error?.takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(this@NovelSpeechService, Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                        }?.let { message ->
                            notifications.notify(NOTIFICATION, Notification.Builder(this@NovelSpeechService, CHANNEL)
                                .setSmallIcon(R.drawable.ic_voice_input).setContentTitle(state.title)
                                .setContentText(getString(message)).setStyle(Notification.BigTextStyle().bigText(getString(message)))
                                .setContentIntent(readerIntent(state)).setAutoCancel(true).build())
                        }
                        stopSelf()
                    }
                } else if (started) update(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val requestToken = intent?.getLongExtra(EXTRA_REQUEST, -1L)
        if (intent?.action == null && !speech.acceptsServiceStart(requestToken)) {
            if (!started) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!speech.isActive) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(speech.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else 0)
            started = true
        }
        when (intent?.action) {
            ACTION_TOGGLE -> speech.toggle()
            ACTION_PREVIOUS -> speech.skipChapter(-1)
            ACTION_NEXT -> speech.skipChapter(1)
            ACTION_STOP -> speech.stop()
            else -> owner = speech.onServiceStarted(requestToken ?: -1L)
        }
        update(speech.state.value)
        return START_NOT_STICKY
    }

    // Media-session notifications are exempt from POST_NOTIFICATIONS; ordinary errors above check permission.
    @SuppressLint("MissingPermission")
    private fun update(state: NovelSpeechState) {
        if (!state.isActive) return
        val playing = state.status != NovelSpeechStatus.PAUSED
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapterTitle).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
        session.setSessionActivity(readerIntent(state))
        notifications.notify(NOTIFICATION, notification(state))
    }

    private fun notification(state: NovelSpeechState): Notification {
        val paused = state.status == NovelSpeechStatus.PAUSED
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_voice_input)
            .setContentTitle(state.title).setContentText(state.chapterTitle)
            .setOnlyAlertOnce(true).setOngoing(!paused).setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(readerIntent(state))
            .setDeleteIntent(actionIntent(ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(Notification.Action.Builder(R.drawable.ic_prev, getString(R.string.prev_chapter),
                actionIntent(ACTION_PREVIOUS)).build())
            .addAction(Notification.Action.Builder(if (paused) R.drawable.ic_play else R.drawable.ic_action_pause,
                getString(if (paused) R.string.novel_speech_resume else R.string.novel_speech_pause),
                actionIntent(ACTION_TOGGLE)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_next, getString(R.string.next_chapter),
                actionIntent(ACTION_NEXT)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_close, getString(R.string.novel_stop_reading_aloud),
                actionIntent(ACTION_STOP)).build())
            .build()
    }

    private fun readerIntent(state: NovelSpeechState): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, NovelReaderActivity::class.java).putExtra(AppRouter.KEY_ID, state.mangaId ?: 0L)
            .apply {
                speech.mangaSnapshot?.let { putExtra(AppRouter.KEY_MANGA, ParcelableManga(it, withDescription = false)) }
            }
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, NovelSpeechService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    override fun onTaskRemoved(rootIntent: Intent?) { speech.stop(); stopSelf(); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        started = false
        unregisterReceiver(noisyReceiver)
        speech.onServiceDestroyed(owner)
        session.release()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_REQUEST = "novel_speech.request"
        private const val CHANNEL = "novel_speech"
        private const val NOTIFICATION = 43
        private const val ACTION_TOGGLE = "novel_speech.toggle"
        private const val ACTION_PREVIOUS = "novel_speech.previous"
        private const val ACTION_NEXT = "novel_speech.next"
        private const val ACTION_STOP = "novel_speech.stop"
    }
}
