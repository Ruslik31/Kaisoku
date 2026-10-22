package org.koitharu.kotatsu.download.ui.worker

import android.content.SharedPreferences
import android.os.SystemClock
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/** Shares chapter admission and start pacing across every source and download worker. */
@Singleton
class DownloadChapterDispatcher @Inject constructor(
    private val settings: AppSettings,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val queue = DownloadChapterQueue { settings.downloadChapterConcurrency }
    private val startGate = DownloadChapterStartGate(SystemClock::elapsedRealtime)

    init {
        settings.subscribe(this)
    }

    suspend fun acquire(): Closeable = queue.acquire()

    suspend fun awaitStart() = startGate.awaitStart(settings.downloadChapterDelay)

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == null || key == AppSettings.KEY_DOWNLOADS_CHAPTER_CONCURRENCY) {
            queue.onLimitChanged()
        }
    }
}
