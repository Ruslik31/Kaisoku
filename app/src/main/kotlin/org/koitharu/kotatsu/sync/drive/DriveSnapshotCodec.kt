package org.koitharu.kotatsu.sync.drive

import android.util.JsonReader
import android.util.JsonWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Base64

object DriveSnapshotCodec {

	const val SCHEMA_VERSION = 1
	private const val CHUNK_SIZE = 48 * 1024

	data class Metadata(
		val schemaVersion: Int,
		val deviceId: String,
		val syncedAt: Long,
		val sourceSettings: Map<String, Map<String, String>>,
	)

	fun write(
		snapshot: File,
		backup: File,
		deviceId: String,
		syncedAt: Long = System.currentTimeMillis(),
		sourceSettings: Map<String, Map<String, String>> = emptyMap(),
	) {
		val digest = calculateSha256(backup)
		JsonWriter(OutputStreamWriter(FileOutputStream(snapshot), Charsets.UTF_8)).use { writer ->
			writer.beginObject()
			writer.name("schema").value(SCHEMA_VERSION.toLong())
			writer.name("device_id").value(deviceId)
			writer.name("synced_at").value(syncedAt)
			writer.name("sha256").value(digest)
			writer.name("source_settings").beginObject()
			sourceSettings.forEach { (source, values) ->
				writer.name(source).beginObject()
				values.forEach { (key, value) -> writer.name(key).value(value) }
				writer.endObject()
			}
			writer.endObject()
			writer.name("backup_chunks").beginArray()
			FileInputStream(backup).use { input ->
				val buffer = ByteArray(CHUNK_SIZE)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					writer.value(Base64.getEncoder().encodeToString(buffer.copyOf(count)))
				}
			}
			writer.endArray()
			writer.endObject()
		}
	}

	fun read(snapshot: File, backup: File): Metadata {
		var schema = 0
		var deviceId = ""
		var syncedAt = 0L
		var expectedHash: String? = null
		var chunksSeen = false
		val sourceSettings = linkedMapOf<String, Map<String, String>>()
		val digest = MessageDigest.getInstance("SHA-256")
		FileOutputStream(backup).use { output ->
			JsonReader(InputStreamReader(FileInputStream(snapshot), Charsets.UTF_8)).use { reader ->
				reader.beginObject()
				while (reader.hasNext()) {
					when (reader.nextName()) {
						"schema" -> schema = reader.nextInt()
						"device_id" -> deviceId = reader.nextString()
						"synced_at" -> syncedAt = reader.nextLong()
						"sha256" -> expectedHash = reader.nextString()
						"source_settings" -> {
							reader.beginObject()
							while (reader.hasNext()) {
								val source = reader.nextName()
								val values = linkedMapOf<String, String>()
								reader.beginObject()
								while (reader.hasNext()) values[reader.nextName()] = reader.nextString()
								reader.endObject()
								sourceSettings[source] = values
							}
							reader.endObject()
						}
						"backup_chunks" -> {
							chunksSeen = true
							reader.beginArray()
							while (reader.hasNext()) {
								val bytes = Base64.getDecoder().decode(reader.nextString())
								digest.update(bytes)
								output.write(bytes)
							}
							reader.endArray()
						}
						else -> reader.skipValue()
					}
				}
				reader.endObject()
			}
		}
		if (schema > SCHEMA_VERSION) throw DriveSchemaException(schema)
		check(schema > 0 && chunksSeen) { "Invalid Google Drive sync snapshot" }
		val actualHash = digest.digest().toHex()
		check(expectedHash == actualHash) { "Google Drive sync snapshot checksum mismatch" }
		return Metadata(schema, deviceId, syncedAt, sourceSettings)
	}

	fun sha256(file: File): String = calculateSha256(file)

	private fun calculateSha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().toHex()
	}

	private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
