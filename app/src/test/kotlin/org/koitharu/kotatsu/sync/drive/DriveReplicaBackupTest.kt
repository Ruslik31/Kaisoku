package org.koitharu.kotatsu.sync.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DriveReplicaBackupTest {
    @Test fun independentNumericCategoryIdsHaveStableIdentityAndRestoreRemapping() {
        withBackup(mapOf("categories" to """[{"category_id":1,"title":"Reading"},{"category_id":2,"title":"Planning"}]""",
            "favourites" to """[{"manga_id":42,"category_id":2}]""")) { file ->
            val values = DriveReplicaBackup.read(file, { _, title -> checkNotNull(title) })
            assertTrue("categories" to "Reading" in values)
            assertTrue("favourites" to "42/Planning" in values)
            val records = values.map { (identity, value) -> DriveReplicaRecord(identity.first, identity.second, 1, "a", value) }
            val restored = File.createTempFile("restored", ".zip")
            try {
                DriveReplicaBackup.write(restored, records) { if (it == "Reading") 70 else 80 }
                val decoded = readEntries(restored)
                assertTrue(decoded.getValue("favourites").contains("\"category_id\":80"))
                assertTrue(decoded.getValue("categories").contains("\"category_id\":70"))
            } finally { restored.delete() }
        }
    }

    @Test fun backupPreservedUidWinsOverNumericIdAndCurrentCategoryTitle() {
        withBackup(mapOf("categories" to """[{"category_id":999,"title":"Renamed","sync_uid":"original"}]""",
            "favourites" to """[{"manga_id":42,"category_id":999}]""")) { file ->
            val values = DriveReplicaBackup.read(file, { _, _ -> error("Already identified backup must retain its UID") })
            assertTrue("categories" to "original" in values)
            assertTrue("favourites" to "42/original" in values)
        }
    }

    @Test fun bookmarksAreMergedIndividuallyRatherThanReplacingAnotherDevicesList() {
        withBackup(mapOf("bookmarks" to """[{"manga":{"id":42},"tags":[],"bookmarks":[{"manga_id":42,"page_id":1},{"manga_id":42,"page_id":2}]}]""")) { file ->
            val values = DriveReplicaBackup.read(file, { _, _ -> error("No category expected") })
            assertEquals(2, values.size)
            assertTrue(values.getValue("bookmarks" to "42:1").toString().contains("\"page_id\":1"))
            assertFalse(values.getValue("bookmarks" to "42:1").toString().contains("\"page_id\":2"))
        }
    }

    @Test fun appAndProviderProfileSecretsAreExcludedWithoutRemovingNormalSettings() {
        withBackup(mapOf("settings" to """[{"theme":"dark","translate_api_key":"secret","translate_api_key_gemini":"secret2","translate_custom_headers_openai":"authorization","translate_model_gemini":"model"}]""")) { file ->
            val values = DriveReplicaBackup.read(file, { _, _ -> error("No category expected") })
            assertEquals(setOf("theme", "translate_model_gemini"), values.keys.mapTo(hashSetOf()) { it.second })
        }
    }

    @Test fun explicitEmptyPreferenceSetsRemainEmptyAndCanonical() {
        withBackup(mapOf("settings" to """[{"disabled":[],"list":["b","a"]}]""")) { file ->
            val values = DriveReplicaBackup.read(file, { _, _ -> error("No category expected") })
            assertEquals(JsonArray(emptyList()), values.getValue("settings" to "disabled"))
            assertEquals(Json.parseToJsonElement("""["a","b"]"""), values.getValue("settings" to "list"))
        }
    }

    @Test fun softDeletedHistoryRetainsHiddenParentForStatisticsOnANewDevice() {
        val history = DriveReplicaRecord("history", "42", 100, "a",
            Json.parseToJsonElement("""{"manga_id":42,"deleted_at":123,"page":9,"manga":{"id":42}}"""))
        val stats = DriveReplicaRecord("statistics", "42:10", 101, "a",
            Json.parseToJsonElement("""{"manga_id":42,"started_at":10,"duration":90,"pages":5}"""))
        assertTrue(history.isDeleted)
        val restored = File.createTempFile("hidden-history-stats", ".zip")
        try {
            DriveReplicaBackup.write(restored, listOf(history, stats)) { error("No category") }
            val entries = readEntries(restored)
            assertTrue(entries.getValue("history").contains("\"deleted_at\":123"))
            assertTrue(entries.getValue("statistics").contains("\"duration\":90"))
        } finally { restored.delete() }
    }

    @Test fun portableCustomCoverDataSurvivesReplicaBridge() {
        withBackup(mapOf("manga_preferences" to """[{"manga":{"id":42},"cover_data":"AQID","cover_extension":"webp"}]""")) { file ->
            val values = DriveReplicaBackup.read(file, { _, _ -> error("No category expected") })
            assertEquals(JsonPrimitive("AQID"), values.getValue("manga_preferences" to "42").jsonObject["cover_data"])
        }
    }

    @Test fun sourceSettingsKeepSourceAndKeyAsSeparateIdentityComponents() {
        withBackup(emptyMap()) { file ->
            val values = DriveReplicaBackup.read(file, { _, _ -> error("No category expected") },
                mapOf("mihon:example/source" to mapOf("domain" to "s:example.org", "password" to "s:secret")))
            assertEquals(1, values.size)
            val key = values.keys.single().second
            assertEquals(Json.parseToJsonElement("""["mihon:example/source","domain"]"""), Json.parseToJsonElement(key))
        }
    }

    private fun withBackup(entries: Map<String, String>, block: (File) -> Unit) {
        val file = File.createTempFile("legacy-backup", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, text) ->
                output.putNextEntry(ZipEntry(name)); output.write(text.toByteArray()); output.closeEntry()
            } }
            block(file)
        } finally { file.delete() }
    }

    private fun readEntries(file: File): Map<String, String> {
        val result = hashMapOf<String, String>()
        ZipInputStream(file.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                result[entry.name] = input.readBytes().decodeToString()
                input.closeEntry(); entry = input.nextEntry
            }
        }
        return result
    }
}
