package org.koitharu.kotatsu.sync.drive

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SavedMetadataDriveBackupTest {
    @Test fun dynamicTitleFlagsRemainIndependentBooleanRecordsAcrossReplicaRoundTrip() {
        val original = File.createTempFile("saved-metadata-backup", ".zip")
        val restored = File.createTempFile("saved-metadata-restored", ".zip")
        try {
            ZipOutputStream(original.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("settings"))
                output.write("""[{"saved_metadata_only_10":true,"saved_metadata_only_-20":false,"saved_metadata_unavailable_sources":true}]""".toByteArray())
                output.closeEntry()
            }
            val values = DriveReplicaBackup.read(original, { _, _ -> error("No categories") })
            assertEquals(3, values.size)
            assertEquals(JsonPrimitive(true), values["settings" to "saved_metadata_only_10"])
            assertEquals(JsonPrimitive(false), values["settings" to "saved_metadata_only_-20"])
            val records = values.map { (key, value) -> DriveReplicaRecord(key.first, key.second, 100, "device", value) }
            DriveReplicaBackup.write(restored, records) { error("No categories") }
            assertEquals(values, DriveReplicaBackup.read(restored, { _, _ -> error("No categories") }))
            DriveReplicaCodec.write(original, DriveReplica(device = "device", sections = setOf("settings"), records = records))
            assertEquals(records, DriveReplicaCodec.read(original).records)
        } finally {
            original.delete()
            restored.delete()
        }
    }
}
