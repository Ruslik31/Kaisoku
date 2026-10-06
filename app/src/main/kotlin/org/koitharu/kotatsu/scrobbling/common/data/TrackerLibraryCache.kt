package org.koitharu.kotatsu.scrobbling.common.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry

/** Remote entries do not need local manga records and are never shared between accounts. */
internal class TrackerLibraryCache(private val prefs: SharedPreferences) {
    private fun key(service: ScrobblerService, account: Long) = "${service.name}_$account"

    fun read(service: ScrobblerService, account: Long): List<TrackerLibraryEntry>? {
        val json = prefs.getString(key(service, account), null) ?: return null
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { TrackerLibraryEntry.fromJson(array.getJSONObject(it)) }
                .also { entries -> require(entries.all { it.service == service }) }
        }.getOrNull()
    }

    fun isFresh(service: ScrobblerService, account: Long, now: Long): Boolean {
        val savedAt = prefs.getLong("${key(service, account)}_updated", 0L)
        return savedAt > 0 && now >= savedAt && now - savedAt < 300_000
    }

    fun write(service: ScrobblerService, account: Long, entries: List<TrackerLibraryEntry>, now: Long) {
        require(entries.all { it.service == service })
        prefs.edit {
            putString(key(service, account), JSONArray().also { array -> entries.forEach { array.put(it.toJson()) } }.toString())
            putLong("${key(service, account)}_updated", now)
        }
    }

    fun invalidate(service: ScrobblerService, account: Long) {
        prefs.edit { remove("${key(service, account)}_updated") }
    }
}
