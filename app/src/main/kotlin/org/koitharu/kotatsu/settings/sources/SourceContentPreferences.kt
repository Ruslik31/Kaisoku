package org.koitharu.kotatsu.settings.sources

import android.content.SharedPreferences
import androidx.core.content.edit
import org.koitharu.kotatsu.core.prefs.AppSettings

/** A malformed imported value must not crash AndroidX preference inflation. Only these new keys are repaired. */
internal fun normalizeSourceContentPreferences(preferences: SharedPreferences) {
    val values = preferences.all
    preferences.edit {
        values[AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED]?.let {
            if (it !is Boolean) remove(AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED)
        }
        values[AppSettings.KEY_SOURCE_CONTENT_CLASSES]?.let {
            if (it !is Set<*> || it.any { value -> value !is String }) remove(AppSettings.KEY_SOURCE_CONTENT_CLASSES)
        }
    }
}
