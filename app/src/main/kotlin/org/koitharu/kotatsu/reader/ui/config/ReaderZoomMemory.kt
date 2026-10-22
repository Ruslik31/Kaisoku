package org.koitharu.kotatsu.reader.ui.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ReaderZoomScope { STANDARD, REVERSED, VERTICAL, DOUBLE, WEBTOON }

@Singleton
class ReaderZoomMemory @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    val isEnabled: Boolean get() = runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun get(scope: ReaderZoomScope): Float? = if (isEnabled) {
        val key = key(scope)
        try {
            validZoomRatio(prefs.getFloat(key, Float.NaN))
        } catch (_: ClassCastException) {
            // Native JSON may decode a whole-number Float as Int/Long; migrate only our own valid numeric key.
            restoredZoomRatio(prefs.all[key])?.also { ratio -> prefs.edit { putFloat(key, ratio) } }
        }
    } else null

    fun record(scope: ReaderZoomScope, ratio: Float) {
        if (!isEnabled) return
        val value = validZoomRatio(ratio) ?: return
        if (get(scope) != value) prefs.edit { putFloat(key(scope), value) }
    }

    companion object {
        const val KEY_ENABLED = "reader_remember_zoom"
        internal fun key(scope: ReaderZoomScope) = "reader_remember_zoom_${scope.name.lowercase(java.util.Locale.ROOT)}"

        fun normalizePreferences(prefs: SharedPreferences) {
            val enabled = prefs.all[KEY_ENABLED]
            if (enabled != null && enabled !is Boolean) prefs.edit { remove(KEY_ENABLED) }
        }
    }
}

internal fun validZoomRatio(ratio: Float): Float? = ratio.takeIf { it.isFinite() && it in .01f..256f }

internal fun restoredZoomRatio(value: Any?): Float? = (value as? Number)?.toFloat()?.let(::validZoomRatio)

internal fun relativeReaderZoom(scale: Float, fit: Float): Float? =
    if (scale.isFinite() && scale > 0f && fit.isFinite() && fit > 0f) validZoomRatio(scale / fit) else null

internal fun rememberedPageScale(ratio: Float?, fit: Float, min: Float, max: Float): Float? {
    if (ratio == null || validZoomRatio(ratio) == null || !fit.isFinite() || fit <= 0f ||
        !min.isFinite() || min <= 0f || !max.isFinite() || max < min) return null
    return (ratio * fit).takeIf { it.isFinite() }?.coerceIn(min, max)
}

/** The pinned SSIV's double-tap animation ends exactly at one of these public scale bounds. */
internal fun isDoubleTapZoomComplete(scale: Float, min: Float, max: Float, target: Float): Boolean =
    scale.isFinite() && min.isFinite() && max.isFinite() && target.isFinite() && min > 0f && max >= min &&
        (scale == min || scale == target.coerceIn(min, max))
