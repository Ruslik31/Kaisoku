package org.koitharu.kotatsu.sync.drive

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.doubleOrNull
import org.koitharu.kotatsu.core.prefs.AppSettings
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.SourceSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Preferences aren't SQLite-transactional. Apply each key on main only if it still matches the captured value. */
@Singleton
class DriveReplicaPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val versions: DrivePreferenceVersions,
) {
    fun isPreference(section: String) = section in setOf("settings", "reader_grid", "saved_filters", "source_settings")

    suspend fun apply(
        records: List<DriveReplicaRecord>,
        captured: Map<Pair<String, String>, JsonElement>,
        device: String,
        observedClock: Long,
    ): List<DriveReplicaRecord> = withContext(Dispatchers.Main.immediate) {
        val result = mutableListOf<DriveReplicaRecord>()
        var clock = observedClock
        val pending = mutableListOf<Pair<SharedPreferences.Editor, Pair<SharedPreferences, Set<String>>>>()
        for ((file, proposed) in records.filter { isPreference(it.section) }.groupBy { resolve(it).file }) {
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val before = prefs.all
            val editor = prefs.edit()
            val watches = hashSetOf<String>()
            for (record in proposed) {
                val location = resolve(record)
                watches += location.watch
                versions.watch(location.watch, prefs)
                val current = if (location.key in before) encode(record.section, before[location.key]) else null
                val expected = captured[record.identity]
                val applied = if (!equivalent(current, expected)) {
                    // A user edit made after capture wins, even if it restores an older value or removes a key.
                    clock = DriveReplicaMerge.nextVersion(clock, System.currentTimeMillis())
                    record.copy(version = clock, author = device, value = current)
                } else record
                if (!equivalent(applied.value, current)) put(editor, applied, location.key, before[location.key])
                result += applied
            }
            pending += editor to (prefs to watches)
        }
        // All current values and proposed types have now been checked; no file is partially applied on decoding failure.
        for ((editor, watched) in pending) {
            versions.applying(watched.second, watched.first, clock) { editor.apply() }
        }
        result
    }

    fun watch(recordSection: String, source: String? = null) {
        val location = when (recordSection) {
            "settings" -> Location("${context.packageName}_preferences", "", "settings")
            "reader_grid" -> Location("tap_grid", "", "reader_grid")
            "source_settings" -> Location(SourceSettings.prefsName(MangaSource(checkNotNull(source))), "", "source_settings/$source")
            "saved_filters" -> Location(checkNotNull(source).replace(File.separatorChar, '$'), "", "saved_filters/$source")
            else -> return
        }
        versions.watch(location.watch, context.getSharedPreferences(location.file, Context.MODE_PRIVATE))
    }

    private data class Location(val file: String, val key: String, val watch: String)

    private fun resolve(record: DriveReplicaRecord): Location = when (record.section) {
        "settings" -> Location("${context.packageName}_preferences", record.key, "settings")
        "reader_grid" -> Location("tap_grid", record.key, "reader_grid")
        "saved_filters", "source_settings" -> {
            val key = Json.parseToJsonElement(record.key).jsonArray
            val source = key[0].jsonPrimitive.content
            val name = key[1].jsonPrimitive.content
            if (record.section == "saved_filters") {
                Location(source.replace(File.separatorChar, '$'), "__pf_${name.hashCode()}", "saved_filters/$source")
            } else Location(SourceSettings.prefsName(MangaSource(source)), name, "source_settings/$source")
        }
        else -> error("Unsupported Google Drive preference section")
    }

    private fun encode(section: String, value: Any?): JsonElement? = when {
        section == "source_settings" -> DriveSourceSettingsStore.encode(value)?.let(::JsonPrimitive)
        section == "saved_filters" -> (value as? String)?.let { DriveReplicaBackup.canonical(Json.parseToJsonElement(it)) }
        value == null -> JsonNull
        value is String -> JsonPrimitive(value)
        value is Boolean -> JsonPrimitive(value)
        value is Number -> JsonPrimitive(value)
        value is Set<*> -> DriveReplicaBackup.canonical(JsonArray(value.map { JsonPrimitive(it.toString()) }))
        else -> error("Unsupported Google Drive preference value")
    }

    private fun equivalent(left: JsonElement?, right: JsonElement?): Boolean {
        if (left == right) return true
        if (left !is JsonPrimitive || right !is JsonPrimitive || left.isString || right.isString) return false
        val a = left.doubleOrNull ?: return false
        val b = right.doubleOrNull ?: return false
        return a.isFinite() && b.isFinite() && a == b
    }

    private fun put(editor: SharedPreferences.Editor, record: DriveReplicaRecord, key: String, original: Any?) {
        val value = record.value
        if (value == null || value == JsonNull) {
            editor.remove(key)
        } else if (record.section == "saved_filters") {
            editor.putString(key, value.toString())
        } else if (record.section == "source_settings") {
            val encoded = value.jsonPrimitive.content
            when (encoded.substringBefore(':')) {
                "b" -> editor.putBoolean(key, encoded.substring(2).toBooleanStrict())
                "i" -> editor.putInt(key, encoded.substring(2).toInt())
                "l" -> editor.putLong(key, encoded.substring(2).toLong())
                "f" -> editor.putFloat(key, encoded.substring(2).toFloat())
                "s" -> editor.putString(key, encoded.substring(2))
                else -> error("Invalid Google Drive source setting")
            }
        } else if (value is JsonArray) {
            editor.putStringSet(key, value.mapTo(hashSetOf()) { it.jsonPrimitive.content })
        } else {
            val primitive = value.jsonPrimitive
            when {
                primitive.isString -> editor.putString(key, primitive.content)
                primitive.booleanOrNull != null -> editor.putBoolean(key, checkNotNull(primitive.booleanOrNull))
                original is Float && primitive.floatOrNull?.isFinite() == true -> editor.putFloat(key, checkNotNull(primitive.floatOrNull))
                (original is Long || key == AppSettings.KEY_LAST_AUTO_PLUGINS) && primitive.longOrNull != null -> editor.putLong(key, checkNotNull(primitive.longOrNull))
                primitive.intOrNull != null -> editor.putInt(key, checkNotNull(primitive.intOrNull))
                primitive.longOrNull != null -> editor.putLong(key, checkNotNull(primitive.longOrNull))
                primitive.floatOrNull != null -> editor.putFloat(key, checkNotNull(primitive.floatOrNull))
                else -> error("Invalid Google Drive preference")
            }
        }
    }
}
