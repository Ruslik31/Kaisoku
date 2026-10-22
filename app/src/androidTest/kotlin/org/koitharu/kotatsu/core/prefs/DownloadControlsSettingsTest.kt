package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R

@RunWith(AndroidJUnit4::class)
class DownloadControlsSettingsTest {

    @Test
    fun missingControlsKeepExistingDefaultsWithoutWritingNewValues() = withPreferences { context ->
        val settings = AppSettings(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val before = preferences.all
        assertEquals(0L, settings.downloadRequestInterval)
        assertEquals(0L, settings.downloadChapterDelay)
        assertEquals(0, settings.downloadChapterConcurrency)
        settings.sanitizeDownloadControls()
        assertEquals(before, preferences.all)
        assertFalse(preferences.contains(AppSettings.KEY_DOWNLOADS_REQUEST_INTERVAL))
        assertFalse(preferences.contains(AppSettings.KEY_DOWNLOADS_CHAPTER_DELAY))
        assertFalse(preferences.contains(AppSettings.KEY_DOWNLOADS_CHAPTER_CONCURRENCY))
    }

    @Test
    fun malformedRestoredControlsCannotCrashRuntimeGettersOrPreferenceInflation() = withPreferences { context ->
        val settings = AppSettings(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit()
            .putInt(AppSettings.KEY_DOWNLOADS_REQUEST_INTERVAL, 5_000)
            .putStringSet(AppSettings.KEY_DOWNLOADS_CHAPTER_DELAY, setOf("1000"))
            .putBoolean(AppSettings.KEY_DOWNLOADS_CHAPTER_CONCURRENCY, true)
            .putString(AppSettings.KEY_DOWNLOADS_METERED_NETWORK, TriStateOption.DISABLED.name)
            .putString(AppSettings.KEY_DOWNLOADS_FORMAT, DownloadFormat.MULTIPLE_CBZ.name)
            .commit()
        assertEquals(0L, settings.downloadRequestInterval)
        assertEquals(0L, settings.downloadChapterDelay)
        assertEquals(0, settings.downloadChapterConcurrency)
        settings.sanitizeDownloadControls()
        assertEquals(TriStateOption.DISABLED, settings.allowDownloadOnMeteredNetwork)
        assertEquals(DownloadFormat.MULTIPLE_CBZ, settings.preferredDownloadFormat)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = ContextThemeWrapper(context, R.style.Theme_Kotatsu)
            val screen = PreferenceManager(themed).inflateFromResource(themed, R.xml.pref_downloads, null)
            for (key in listOf(AppSettings.KEY_DOWNLOADS_REQUEST_INTERVAL,
                AppSettings.KEY_DOWNLOADS_CHAPTER_DELAY, AppSettings.KEY_DOWNLOADS_CHAPTER_CONCURRENCY)) {
                assertEquals("0", screen.findPreference<ListPreference>(key)?.value)
            }
        }
    }

    @Test
    fun validControlsAndUnrelatedPreferencesRemainUnchanged() = withPreferences { context ->
        val settings = AppSettings(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit()
            .putString(AppSettings.KEY_DOWNLOADS_REQUEST_INTERVAL, "5000")
            .putString(AppSettings.KEY_DOWNLOADS_CHAPTER_DELAY, "30000")
            .putString(AppSettings.KEY_DOWNLOADS_CHAPTER_CONCURRENCY, "2")
            .putString(AppSettings.KEY_DOWNLOADS_METERED_NETWORK, TriStateOption.DISABLED.name)
            .putString(AppSettings.KEY_DOWNLOADS_FORMAT, DownloadFormat.SINGLE_CBZ.name)
            .commit()
        val before = preferences.all
        settings.sanitizeDownloadControls()
        val recreated = AppSettings(context)
        assertEquals(5_000L, recreated.downloadRequestInterval)
        assertEquals(30_000L, recreated.downloadChapterDelay)
        assertEquals(2, recreated.downloadChapterConcurrency)
        assertEquals(before, preferences.all)
    }

    private fun withPreferences(block: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "download-controls-${System.nanoTime()}-"
        val created = hashSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name
                created += scoped
                return app.getSharedPreferences(scoped, mode)
            }
        }
        try {
            block(context)
        } finally {
            created.forEach(app::deleteSharedPreferences)
        }
    }
}
