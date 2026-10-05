package org.koitharu.kotatsu.sync.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Kaisoku schema-2 replicas borrow DropSauce's per-device, versioned-record merge design. */
@Serializable
data class DriveReplica(
    val schema: Int = SCHEMA,
    val device: String,
    val sections: Set<String>,
    val records: List<DriveReplicaRecord>,
    val legacyVersions: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA = 2
    }
}

@Serializable
data class DriveReplicaRecord(
    val section: String,
    val key: String,
    val version: Long,
    val author: String,
    val value: JsonElement? = null,
) {
    val identity: Pair<String, String> get() = section to key
    val isDeleted: Boolean get() = value == null || (section == "history" &&
        ((value as? JsonObject)?.get("deleted_at") as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true)
}

object DriveReplicaMerge {
    /** Tombstones participate in the same ordering; absence never means remote deletion. */
    fun merge(replicas: Iterable<DriveReplica>): Map<Pair<String, String>, DriveReplicaRecord> {
        val result = linkedMapOf<Pair<String, String>, DriveReplicaRecord>()
        for (replica in replicas) {
            require(replica.schema == DriveReplica.SCHEMA) { "Unsupported Google Drive replica schema ${replica.schema}" }
            for (record in replica.records) {
                require(record.version >= 0 && record.section in replica.sections && record.key.isNotEmpty()) {
                    "Invalid Google Drive replica record"
                }
                val old = result[record.identity]
                if (old == null || compare(record, old) > 0) result[record.identity] = record
            }
        }
        return result
    }

    fun compare(left: DriveReplicaRecord, right: DriveReplicaRecord): Int =
        compareValuesBy(left, right, DriveReplicaRecord::version, DriveReplicaRecord::author,
            { if (it.isDeleted) 1 else 0 }, { it.value?.toString().orEmpty() })

    /** A logical clock survives wall-clock rollback and advances beyond all observed remote edits. */
    fun nextVersion(clock: Long, now: Long): Long = maxOf(clock + 1, now.coerceAtLeast(0) * 1000)

    fun recordValue(section: String, value: JsonElement): JsonElement? =
        if (section in setOf("categories", "favourites") &&
            ((value as? JsonObject)?.get("deleted_at") as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true) null
        else value

    data class CategoryMerge(val records: Map<Pair<String, String>, DriveReplicaRecord>, val aliases: Map<String, String>)

    /** Legacy backups have no UUID. Identical category titles are unique locally and identify a legacy alias. */
    fun coalesceCategories(records: Map<Pair<String, String>, DriveReplicaRecord>): CategoryMerge {
        val aliases = hashMapOf<String, String>()
        val categories = records.values.filter { it.section == "categories" && !it.isDeleted }
        for (sameTitle in categories.groupBy { (it.value as JsonObject).getValue("title").toString() }.values) {
            val target = sameTitle.minOf { it.key }
            sameTitle.filter { it.key != target }.forEach { aliases[it.key] = target }
        }
        val result = linkedMapOf<Pair<String, String>, DriveReplicaRecord>()
        for (record in records.values) {
            val oldUid = if (record.section == "categories") record.key
                else if (record.section == "favourites") record.key.substringAfter('/') else null
            val target = oldUid?.let { aliases[it] }
            val normalized = if (target == null) record else {
                val key = if (record.section == "categories") target else "${record.key.substringBefore('/')}/$target"
                val value = (record.value as? JsonObject)?.let { JsonObject(it + ("sync_uid" to JsonPrimitive(target))) }
                record.copy(key = key, value = value)
            }
            val prior = result[normalized.identity]
            if (prior == null || compare(normalized, prior) > 0) result[normalized.identity] = normalized
        }
        return CategoryMerge(result, aliases)
    }

    fun normalizeCategories(record: DriveReplicaRecord, resolve: (String) -> String): DriveReplicaRecord {
        val uid = when (record.section) {
            "categories" -> resolve(record.key)
            "favourites" -> resolve(record.key.substringAfter('/'))
            else -> return record
        }
        val key = if (record.section == "categories") uid else "${record.key.substringBefore('/')}/$uid"
        val value = (record.value as? JsonObject)?.let { JsonObject(it + ("sync_uid" to JsonPrimitive(uid))) }
        return record.copy(key = key, value = value)
    }

    /** A membership cannot remain live when its owning category has been deleted. */
    fun cascadeCategoryDeletions(records: Map<Pair<String, String>, DriveReplicaRecord>): Map<Pair<String, String>, DriveReplicaRecord> =
        records.mapValues { (_, record) ->
            val category = if (record.section == "favourites") records["categories" to record.key.substringAfter('/')] else null
            if (category?.isDeleted == true && !record.isDeleted) record.copy(value = null,
                version = maxOf(record.version, category.version), author = category.author) else record
        }

    fun content(value: JsonElement?): JsonElement? = if (value is JsonObject) {
        JsonObject(value.filterKeys { it != "manga" && it != "tags" && it != "category_id" && it != "used_at" && it != "added_in" })
    } else value

    fun eventTime(value: JsonElement): Long {
        val objectValue = value as? JsonObject ?: return 0
        return listOf("updated_at", "created_at", "deleted_at", "last_check_time", "started_at")
            .maxOf { (objectValue[it] as? JsonPrimitive)?.longOrNull ?: 0L }.coerceAtLeast(0)
    }
}

object DriveReplicaCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun write(file: File, replica: DriveReplica) {
        GZIPOutputStream(file.outputStream().buffered()).use { output ->
            output.write(json.encodeToString(DriveReplica.serializer(), replica).toByteArray(Charsets.UTF_8))
        }
    }

    fun read(file: File): DriveReplica {
        val data = GZIPInputStream(file.inputStream().buffered()).use { input ->
            val bytes = input.readBounded(MAX_BYTES)
            check(bytes.size <= MAX_BYTES) { "Google Drive replica is too large" }
            bytes.decodeToString()
        }
        return json.decodeFromString(DriveReplica.serializer(), data).also {
            if (it.schema > DriveReplica.SCHEMA) throw DriveSchemaException(it.schema)
            DriveReplicaMerge.merge(listOf(it))
        }
    }

    fun InputStream.readBounded(max: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, max - output.size() + 1))
            if (count < 0) return output.toByteArray()
            check(output.size() + count <= max) { "Google Drive payload is too large" }
            output.write(buffer, 0, count)
        }
    }

    private const val MAX_BYTES = 128 * 1024 * 1024
}
