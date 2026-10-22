package org.koitharu.kotatsu.core.ui.theme

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.edit
import com.google.android.material.color.ColorResourcesOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/** Android 11 only. Android 12+ continues to use the existing system dynamic-color resources. */
@Singleton
class WallpaperPaletteCompat internal constructor(
    context: Context,
    private val readWallpaper: () -> WallpaperSeed?,
    private val now: () -> Long,
    private val applyColors: (Context, Map<Int, Int>) -> Boolean = ::applyWallpaperColors,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context,
        { readSystemWallpaper(context) },
        SystemClock::elapsedRealtime,
    )

    enum class Status { NOT_PREPARED, READY, UNAVAILABLE, OVERRIDE_UNAVAILABLE }

    // Device-only derived data, deliberately outside the ordinary settings/backup/sync preferences.
    private val prefs = context.getSharedPreferences("_wallpaper_palette", Context.MODE_PRIVATE)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var palette: WallpaperPalette? = runCatching {
        if (prefs.contains("seed") && prefs.contains("wallpaper_id")) {
            WallpaperPalette(WallpaperSeed(prefs.getInt("wallpaper_id", -1), prefs.getInt("seed", 0)))
                .takeIf { it.seed.wallpaperId >= 0 }
        } else null
    }.getOrNull()
    @Volatile private var failedOverride: WallpaperSeed? = null
    private var lastChecked: Long? = null
    private var inFlight: Deferred<Status>? = null
    private val mutableStatus = MutableStateFlow(if (palette == null) Status.NOT_PREPARED else Status.READY)
    val status = mutableStatus.asStateFlow()

    /** All callers await the same bounded lookup. Cancellation of one activity does not cancel another. */
    suspend fun refresh(force: Boolean = false): Status {
        if (!isSupported) return Status.UNAVAILABLE
        val request = synchronized(lock) {
            inFlight?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                val instant = now()
                val previous = synchronized(lock) { lastChecked }
                if (!force && previous != null && instant - previous in 0 until MIN_CHECK_INTERVAL) {
                    return@async mutableStatus.value
                }
                synchronized(lock) { lastChecked = instant }
                val seed = withTimeoutOrNull(LOOKUP_TIMEOUT) {
                    runCatchingCancellable { runInterruptible { readWallpaper() } }.getOrNull()
                }
                if (seed == null) {
                    mutableStatus.value = Status.UNAVAILABLE
                } else {
                    if (palette?.seed != seed) {
                        palette = WallpaperPalette(seed)
                        prefs.edit { putInt("seed", seed.color); putInt("wallpaper_id", seed.wallpaperId) }
                    }
                    if (force) failedOverride = null
                    mutableStatus.value = if (failedOverride == seed) Status.OVERRIDE_UNAVAILABLE else Status.READY
                }
                mutableStatus.value
            }.also { inFlight = it }
        }
        request.start()
        return request.await()
    }

    internal val availableSeed: WallpaperSeed?
        get() = palette?.seed?.takeUnless { it == failedOverride }

    /** Apply once before inflation, to an activity with isolated Resources (see [isolateContext]). */
    internal fun apply(context: Context): WallpaperSeed? {
        if (!isSupported) return null
        val current = palette ?: return null
        if (failedOverride == current.seed) return null
        val success = runCatching {
            applyColors(context, current.colors(context.isDark()))
        }.getOrDefault(false)
        if (!success) {
            failedOverride = current.seed
            mutableStatus.value = Status.OVERRIDE_UNAVAILABLE
            return null
        }
        return current.seed
    }

    /** Material creates an isolated Resources instance, so previews cannot recolor other themes. */
    @SuppressLint("RestrictedApi")
    fun preview(context: Context): Context? {
        if (!isSupported) return null
        val current = palette ?: return null
        return runCatching {
            ColorResourcesOverride.getInstance()?.wrapContextIfPossible(context, current.colors(context.isDark()))
                ?.takeUnless { it === context }
        }.getOrNull()
    }

    companion object {
        private const val MIN_CHECK_INTERVAL = 5_000L
        private const val LOOKUP_TIMEOUT = 3_000L
        @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
        val isSupported: Boolean get() = Build.VERSION.SDK_INT == Build.VERSION_CODES.R

        /** Same public configuration-context isolation used by Material's own preview wrapper. */
        fun isolateContext(context: Context): Context = if (isSupported) {
            ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(Configuration()) }
        } else context

        private fun readSystemWallpaper(context: Context): WallpaperSeed? {
            if (!isSupported) return null
            val manager = WallpaperManager.getInstance(context)
            val id = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val color = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() ?: return null
            if (id < 0 || manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) != id) return null
            return WallpaperSeed(id, color)
        }

        private fun Context.isDark() = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}

// Material's pinned public Java helper, backed by API 30 ResourcesLoader.
@SuppressLint("RestrictedApi")
private fun applyWallpaperColors(context: Context, colors: Map<Int, Int>): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ColorResourcesOverride.getInstance()?.applyIfPossible(context, colors) == true
    } else false
