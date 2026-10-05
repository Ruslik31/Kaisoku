package org.koitharu.kotatsu.sync.drive

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.backups.data.SensitiveBackupKeys
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks actual preference edits, including removal, rather than stamping every sync as an edit. */
@Singleton
class DrivePreferenceVersions @Inject constructor(@ApplicationContext private val context: Context) {
    private val backend = context.getSharedPreferences("google_drive_sync", Context.MODE_PRIVATE)
    private val versions = context.getSharedPreferences("google_drive_pref_versions", Context.MODE_PRIVATE)
    private val listeners = hashMapOf<String, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val muted = hashSetOf<String>()
    private val originals = hashMapOf<String, Map<String, *>>()

    fun start() {
        watch("settings", PreferenceManager.getDefaultSharedPreferences(context))
        watch("reader_grid", context.getSharedPreferences("tap_grid", Context.MODE_PRIVATE))
    }

    @Synchronized
    fun watch(section: String, prefs: SharedPreferences) {
        if (section in listeners) return
        originals[section] = prefs.all
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            synchronized(this) {
                if (section in muted) return@synchronized
                val before = originals[section].orEmpty()
                val after = changed.all
                if (backend.getString(SyncBackendSettings.KEY_BACKEND, null) != SyncBackend.GOOGLE_DRIVE.name) {
                    originals[section] = after
                    return@synchronized
                }
                val keys = if (key == null) before.keys + after.keys else setOf(key)
                val editor = versions.edit()
                var clock = versions.getLong("_clock", 0L)
                for (item in keys) if ((!section.startsWith("source_settings/") || item in DriveSourceSettingsStore.ALLOWED_KEYS) &&
                    (!section.startsWith("saved_filters/") || item.startsWith("__pf_")) && before[item] != after[item] &&
                    (section != "settings" || !SensitiveBackupKeys.isSensitive(item))) {
                    clock = DriveReplicaMerge.nextVersion(clock, System.currentTimeMillis())
                    editor.putLong("$section/$item", clock)
                }
                originals[section] = after
                editor.putLong("_clock", clock).apply()
            }
        }
        listeners[section] = listener // SharedPreferences keeps weak references to listeners.
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    @Synchronized
    fun deletions(): Map<Pair<String, String>, Long> {
        val result = hashMapOf<Pair<String, String>, Long>()
        for ((scope, original) in originals) {
            for ((name, value) in versions.all) if (name.startsWith("$scope/")) {
                val key = name.removePrefix("$scope/")
                if (key !in original && value is Long) result[scope to key] = value
            }
        }
        return result
    }

    @Synchronized
    fun version(section: String, key: String): Long = versions.getLong("$section/$key", 0L)

    @Synchronized
    fun applying(scopes: Set<String>, prefs: SharedPreferences, clock: Long, block: () -> Unit) {
        muted += scopes
        try { block() } finally {
            for (scope in scopes) acknowledge(scope, prefs, clock)
            muted -= scopes
        }
    }

    @Synchronized
    fun acknowledge(section: String, prefs: SharedPreferences, clock: Long) {
        // Call before returning to the main thread: queued apply notifications are then no-ops.
        originals[section] = prefs.all
        versions.edit().putLong("_clock", maxOf(clock, versions.getLong("_clock", 0L))).apply()
    }
}
