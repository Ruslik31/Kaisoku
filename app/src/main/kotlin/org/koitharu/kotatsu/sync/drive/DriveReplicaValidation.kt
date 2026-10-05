package org.koitharu.kotatsu.sync.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.floatOrNull
import org.koitharu.kotatsu.backups.data.model.BookmarkBackup
import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.MangaPreferencesBackup
import org.koitharu.kotatsu.backups.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backups.data.model.SourceBackup
import org.koitharu.kotatsu.backups.data.model.StatisticBackup
import org.koitharu.kotatsu.backups.data.model.TrackBackup
import org.koitharu.kotatsu.filter.data.PersistableFilter
import java.util.Base64

/** Decode all selected records before touching preferences or writing any cover file. */
object DriveReplicaValidation {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(records: List<DriveReplicaRecord>) {
        for (record in records) {
            if (record.section == "saved_filters" || record.section == "source_settings") {
                val key = Json.parseToJsonElement(record.key).jsonArray
                require(key.size == 2 && key.all { it is JsonPrimitive && it.isString && it.content.isNotEmpty() && '\u0000' !in it.content })
                if (record.section == "source_settings") require(key[1].jsonPrimitive.content in DriveSourceSettingsStore.ALLOWED_KEYS)
            }
            val value = record.value ?: continue
            when (record.section) {
                "categories" -> json.decodeFromJsonElement<CategoryBackup>(value)
                "favourites" -> json.decodeFromJsonElement<FavouriteBackup>(value)
                "history" -> json.decodeFromJsonElement<HistoryBackup>(value)
                "bookmarks" -> json.decodeFromJsonElement<BookmarkBackup>(value)
                "sources" -> json.decodeFromJsonElement<SourceBackup>(value)
                "scrobbling" -> json.decodeFromJsonElement<ScrobblingBackup>(value)
                "statistics" -> json.decodeFromJsonElement<StatisticBackup>(value)
                "tracks" -> json.decodeFromJsonElement<TrackBackup>(value)
                "manga_preferences" -> {
                    val prefs = json.decodeFromJsonElement<MangaPreferencesBackup>(value)
                    prefs.coverData?.let {
                        require(it.length <= MAX_COVER_BYTES * 4 / 3 + 4) { "Google Drive cover is too large" }
                        require(Base64.getDecoder().decode(it).size in 1..MAX_COVER_BYTES) { "Invalid Google Drive cover" }
                    }
                }
                "saved_filters" -> json.decodeFromJsonElement<PersistableFilter>(value)
                "source_settings" -> {
                    val encoded = value.jsonPrimitive.content
                    require(encoded.length >= 2 && encoded[1] == ':')
                    when (encoded[0]) {
                        'b' -> encoded.substring(2).toBooleanStrict()
                        'i' -> encoded.substring(2).toInt()
                        'l' -> encoded.substring(2).toLong()
                        'f' -> require(encoded.substring(2).toFloat().isFinite())
                        's' -> Unit
                        else -> error("Invalid Google Drive source setting")
                    }
                }
                "settings", "reader_grid" -> {
                    require('\u0000' !in record.key)
                    require(value == JsonNull || (value is JsonArray && value.all { it is JsonPrimitive && it.isString }) ||
                        (value is JsonPrimitive && (value.isString || value.booleanOrNull != null ||
                            value.intOrNull != null || value.longOrNull != null || value.floatOrNull?.isFinite() == true)))
                }
                else -> error("Unknown Google Drive section ${record.section}")
            }
        }
    }

    private const val MAX_COVER_BYTES = 10 * 1024 * 1024
}
