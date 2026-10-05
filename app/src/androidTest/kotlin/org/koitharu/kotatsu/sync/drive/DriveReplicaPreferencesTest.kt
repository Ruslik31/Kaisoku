package org.koitharu.kotatsu.sync.drive

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveReplicaPreferencesTest {
    @Test fun unchangedCapturedValueAcceptsRemoteEditWithoutEchoRevision() = withScope { context -> runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        withContext(Dispatchers.Main) { prefs.edit().putString("theme", "light").apply() }
        val versions = DrivePreferenceVersions(context).also { it.start() }
        val applier = DriveReplicaPreferences(context, versions)
        val record = DriveReplicaRecord("settings", "theme", 100, "remote", JsonPrimitive("dark"))
        val result = applier.apply(listOf(record), mapOf(record.identity to JsonPrimitive("light")), "local", 100)
        assertEquals("dark", prefs.getString("theme", null))
        assertEquals(record, result.single())
        assertEquals(0L, versions.version("settings", "theme"))
        withContext(Dispatchers.Main) { prefs.edit().putString("theme", "blue").apply() }
        assertTrue(versions.version("settings", "theme") > 100)
    } }

    @Test fun userEditAfterCaptureWinsOverNewerRemoteValue() = withScope { context -> runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val versions = DrivePreferenceVersions(context).also { it.start() }
        withContext(Dispatchers.Main) { prefs.edit().putString("theme", "my choice").apply() }
        val record = DriveReplicaRecord("settings", "theme", 100, "remote", JsonPrimitive("remote choice"))
        val result = DriveReplicaPreferences(context, versions).apply(listOf(record),
            mapOf(record.identity to JsonPrimitive("captured choice")), "local", 100)
        assertEquals("my choice", prefs.getString("theme", null))
        assertEquals(JsonPrimitive("my choice"), result.single().value)
        assertEquals("local", result.single().author)
        assertTrue(result.single().version > record.version)
    } }

    @Test fun userRemovalAfterCaptureStaysRemovedAndPublishesTombstone() = withScope { context -> runBlocking {
        val versions = DrivePreferenceVersions(context).also { it.start() }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val record = DriveReplicaRecord("settings", "theme", 100, "remote", JsonPrimitive("remote"))
        val result = DriveReplicaPreferences(context, versions).apply(listOf(record),
            mapOf(record.identity to JsonPrimitive("old")), "local", 100)
        assertFalse(prefs.contains("theme"))
        assertTrue(result.single().isDeleted)
        assertEquals("local", result.single().author)
    } }

    @Test fun explicitEmptySetSurvivesConcurrentRemoteRefresh() = withScope { context -> runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val versions = DrivePreferenceVersions(context).also { it.start() }
        withContext(Dispatchers.Main) { prefs.edit().putStringSet("selected", emptySet()).apply() }
        val record = DriveReplicaRecord("settings", "selected", 100, "remote", JsonArray(listOf(JsonPrimitive("remote"))))
        val result = DriveReplicaPreferences(context, versions).apply(listOf(record),
            mapOf(record.identity to JsonArray(listOf(JsonPrimitive("old")))), "local", 100)
        assertEquals(emptySet<String>(), prefs.getStringSet("selected", null))
        assertEquals(JsonArray(emptyList()), result.single().value)
    } }

    @Test fun malformedCurrentFilterDoesNotPartiallyApplyAnotherPreferenceFile() = withScope { context -> runBlocking {
        val versions = DrivePreferenceVersions(context).also { it.start() }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("theme", "light").commit()
        context.getSharedPreferences("testsource", Context.MODE_PRIVATE).edit()
            .putString("__pf_${"filter".hashCode()}", "broken JSON").commit()
        val theme = DriveReplicaRecord("settings", "theme", 100, "remote", JsonPrimitive("dark"))
        val filter = DriveReplicaRecord("saved_filters", "[\"testsource\",\"filter\"]", 100, "remote", JsonPrimitive("ignored"))
        try {
            DriveReplicaPreferences(context, versions).apply(listOf(theme, filter),
                mapOf(theme.identity to JsonPrimitive("light")), "local", 100)
            fail("Malformed current filter must fail before applying any prepared editor")
        } catch (_: kotlinx.serialization.SerializationException) { }
        assertEquals("light", prefs.getString("theme", null))
        assertEquals("broken JSON", context.getSharedPreferences("testsource", Context.MODE_PRIVATE)
            .getString("__pf_${"filter".hashCode()}", null))
    } }

    @Test fun clearingPreferencesRetainsDeletionVersionsEvenBeforeFirstSync() = withScope { context -> runBlocking {
        val versions = DrivePreferenceVersions(context).also { it.start() }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        withContext(Dispatchers.Main) {
            prefs.edit().putString("theme", "dark").apply()
            prefs.edit().remove("theme").apply()
        }
        assertTrue(checkNotNull(versions.deletions()["settings" to "theme"]) > 0)
    } }

    private fun withScope(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "drive-test-${System.nanoTime()}-"
        val created = hashSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name
                created += scoped
                return base.getSharedPreferences(scoped, mode)
            }
        }
        context.getSharedPreferences("google_drive_sync", Context.MODE_PRIVATE).edit()
            .putString(SyncBackendSettings.KEY_BACKEND, SyncBackend.GOOGLE_DRIVE.name).commit()
        try { block(context) } finally { created.forEach(base::deleteSharedPreferences) }
    }
}
