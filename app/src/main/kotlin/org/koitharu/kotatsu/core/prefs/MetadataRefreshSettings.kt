package org.koitharu.kotatsu.core.prefs

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.util.ext.observeChanges
import javax.inject.Inject
import javax.inject.Singleton

/** Stored with ordinary settings, including the existing per-key native/Drive backup paths. */
@Singleton
class MetadataRefreshSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var useSavedMetadataForUnavailableSources: Boolean
        get() = prefs.getBoolean(KEY_UNAVAILABLE_SOURCES, false)
        set(value) = synchronized(prefs) { prefs.edit { putBoolean(KEY_UNAVAILABLE_SOURCES, value) } }

    fun isSavedMetadataOnly(mangaId: Long): Boolean = prefs.getBoolean(titleKey(mangaId), false)

    fun setSavedMetadataOnly(mangaId: Long, value: Boolean) = synchronized(prefs) {
        // Keep explicit false values: they take precedence during a later identity merge.
        prefs.edit { putBoolean(titleKey(mangaId), value) }
    }

    fun observeSavedMetadataOnly(mangaId: Long) = prefs.observeChanges()
        .onStart { emit(null) }
        .map { isSavedMetadataOnly(mangaId) }
        .distinctUntilChanged()

    fun retainedMangaIds(): Set<Long> = prefs.all.mapNotNullTo(hashSetOf()) { (key, value) ->
        if (value == true && key.startsWith(TITLE_KEY_PREFIX)) key.removePrefix(TITLE_KEY_PREFIX).toLongOrNull() else null
    }

    /** Call only after the database transaction which returned [mergedIds] has committed. */
    fun copyMergedTitleFlags(mergedIds: Map<Long, Long>) = synchronized(prefs) {
        val missing = missingMergedMetadataFlags(prefs.all, mergedIds)
        if (missing.isNotEmpty()) {
            prefs.edit { missing.forEach { (key, value) -> putBoolean(key, value) } }
        }
    }

    companion object {
        const val KEY_UNAVAILABLE_SOURCES = "saved_metadata_unavailable_sources"
        const val TITLE_KEY_PREFIX = "saved_metadata_only_"

        fun titleKey(mangaId: Long): String = TITLE_KEY_PREFIX + mangaId
    }
}

internal fun missingMergedMetadataFlags(values: Map<String, *>, mergedIds: Map<Long, Long>): Map<String, Boolean> {
    val missing = linkedMapOf<String, Boolean>()
    val resolved = mergedIds.keys.mapNotNull { oldId ->
        val visited = hashSetOf(oldId)
        var target = oldId
        var distance = 0
        while (true) {
            val next = mergedIds[target] ?: break
            if (next == target) break
            if (!visited.add(next)) return@mapNotNull null // Ambiguous cycle: do not invent a canonical title.
            target = next
            distance++
        }
        if (target == oldId) null else Triple(oldId, target, distance)
    }.sortedWith(compareBy<Triple<Long, Long, Int>> { it.third }.thenBy { it.first })
    for ((oldId, newId, _) in resolved) {
        val oldKey = MetadataRefreshSettings.titleKey(oldId)
        val newKey = MetadataRefreshSettings.titleKey(newId)
        val oldValue = values[oldKey] as? Boolean ?: continue
        if (newKey !in values && newKey !in missing) missing[newKey] = oldValue
    }
    return missing
}
