package org.koitharu.kotatsu.core.diagnostics

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import java.io.File

/** Private, bounded, opt-in breadcrumbs. Deliberately separate from crash-report upload and backups. */
internal object LocalDiagnostics {
    const val KEY = "local_diagnostics"
    private const val MAX_BYTES = 65_536
    private val pending = DiagnosticBuffer()
    private val storage = Mutex()
    @Volatile var enabled = false
        private set
    private var output: File? = null
    private var flushJob: Job? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun start(app: Application, settings: AppSettings) {
        output = File(app.noBackupFilesDir, "local-diagnostics.txt")
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) {
                enabled = settings.isLocalDiagnosticsEnabled
                if (enabled) record(DiagnosticEvent.PROCESS) else {
                    synchronized(this) { flushJob?.cancel(); flushJob = null }
                    pending.clear()
                }
            }
        }.also(settings::subscribe)
        enabled = settings.isLocalDiagnosticsEnabled
        record(DiagnosticEvent.PROCESS)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { record(DiagnosticEvent.SCREEN_OPEN, area(activity)) }
            override fun onActivityPaused(activity: Activity) { record(DiagnosticEvent.SCREEN_CLOSE, area(activity)) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun record(event: DiagnosticEvent, area: DiagnosticArea = DiagnosticArea.APP, status: Int = 0,
               duration: Long = 0, fingerprint: Int = 0) {
        if (!enabled) return
        pending.add(System.currentTimeMillis(), event, area, status, duration, fingerprint)
        scheduleFlush()
    }

    fun error(error: Throwable, crash: Boolean = false) {
        if (!enabled) return
        // Messages and stack-frame text are never recorded. The fingerprint helps correlate repeated failures.
        val fingerprint = error.javaClass.name.hashCode() * 31 + (error.stackTrace.firstOrNull()?.let {
            it.className.hashCode() * 31 + it.lineNumber
        } ?: 0)
        val category = when (error) {
            is OutOfMemoryError -> 1
            is java.net.UnknownHostException -> 2
            is java.net.SocketTimeoutException -> 3
            is java.io.IOException -> 4
            is IllegalArgumentException -> 5
            is IllegalStateException -> 6
            is LinkageError -> 7
            else -> 8
        }
        record(if (crash) DiagnosticEvent.CRASH else DiagnosticEvent.ERROR, status = category, fingerprint = fingerprint)
    }

    fun flushOnCrash() {
        if (!enabled || !storage.tryLock()) return
        try {
            val file = output ?: return
            runCatching { file.writeText(bounded(read(file) + "\n" + pending.drain())) }
        } finally { storage.unlock() }
    }

    @Synchronized private fun scheduleFlush() {
        if (flushJob != null || !enabled) return
        flushJob = processLifecycleScope.launch(Dispatchers.IO) {
            try { delay(5_000); persist() }
            finally { synchronized(this@LocalDiagnostics) {
                flushJob = null
                if (!pending.isEmpty() && enabled) scheduleFlush()
            } }
        }
    }

    private suspend fun persist() = storage.withLock {
        val file = output ?: return@withLock
        if (!enabled) return@withLock
        val batch = pending.drain()
        if (batch.isEmpty()) return@withLock
        runCatching {
            val text = bounded(read(file) + "\n" + batch)
            val temp = File(file.parentFile, "local-diagnostics.tmp")
            try {
                temp.writeText(text)
                if (!temp.renameTo(file)) throw java.io.IOException("Diagnostic write failed")
            } finally { temp.delete() }
        }
    }

    suspend fun export(): String = withContext(Dispatchers.IO) { storage.withLock {
        val text = bounded((output?.let(::read).orEmpty() + "\n" + pending.snapshot()).trim())
        if (text.isEmpty()) "" else "Kaisoku ${BuildConfig.VERSION_NAME} SDK ${android.os.Build.VERSION.SDK_INT}\n" +
            "Local diagnostic codes; no request content.\n" +
            "Heap ${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}/${Runtime.getRuntime().maxMemory()}\n" + text
    } }

    suspend fun clear() = withContext(Dispatchers.IO) { storage.withLock {
        pending.clear()
        output?.writeText("")
    } }

    private fun read(file: File): String = runCatching {
        if (!file.exists()) "" else file.inputStream().use { input ->
            if (file.length() > MAX_BYTES) input.skip(file.length() - MAX_BYTES)
            input.readBytes().decodeToString()
        }
    }.getOrDefault("")
    private fun bounded(text: String): String = if (text.length <= MAX_BYTES) text else text.takeLast(MAX_BYTES).substringAfter('\n')
    private fun area(activity: Activity): DiagnosticArea {
        val name = activity.javaClass.name
        return when {
            ".novel." in name -> DiagnosticArea.NOVEL
            ".reader." in name -> DiagnosticArea.READER
            ".details." in name -> DiagnosticArea.DETAILS
            ".settings." in name -> DiagnosticArea.SETTINGS
            ".download." in name -> DiagnosticArea.DOWNLOADS
            ".sources." in name -> DiagnosticArea.SOURCES
            else -> DiagnosticArea.OTHER
        }
    }
}
