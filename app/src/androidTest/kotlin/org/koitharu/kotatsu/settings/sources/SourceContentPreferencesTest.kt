package org.koitharu.kotatsu.settings.sources

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.SourceContentClass
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.xmlpull.v1.XmlPullParser
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceContentPreferencesTest {
    @Test fun defaultsFollowTheExistingRestrictionAndAnEmptySelectionStaysEmpty() = fixture { context, prefs ->
        val settings = AppSettings(context)
        assertFalse(settings.useSeparateSourceContentClasses)
        assertEquals(setOf(SourceContentClass.SAFE), settings.sourceContentPolicy.selectedClasses)
        prefs.edit().putBoolean(AppSettings.KEY_DISABLE_NSFW, false).commit()
        assertEquals(SourceContentClass.entries.toSet(), settings.sourceContentPolicy.selectedClasses)
        prefs.edit().putBoolean(AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED, true)
            .putStringSet(AppSettings.KEY_SOURCE_CONTENT_CLASSES, emptySet()).commit()
        normalizeSourceContentPreferences(prefs)
        assertTrue(settings.useSeparateSourceContentClasses)
        assertTrue(settings.sourceContentPolicy.selectedClasses.isEmpty())
        assertFalse(settings.isNsfwContentDisabled)
    }

    @Test fun repairOnlyRemovesMalformedNewKeys() = fixture { context, prefs ->
        prefs.edit().putBoolean(AppSettings.KEY_DISABLE_NSFW, true).putString("existing_setting", "keep")
            .putString(AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED, "true")
            .putInt(AppSettings.KEY_SOURCE_CONTENT_CLASSES, 2).commit()
        normalizeSourceContentPreferences(prefs)
        assertFalse(prefs.contains(AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED))
        assertFalse(prefs.contains(AppSettings.KEY_SOURCE_CONTENT_CLASSES))
        assertEquals("keep", prefs.getString("existing_setting", null))
        assertTrue(AppSettings(context).isNsfwContentDisabled)
    }

    @Test fun xmlDoesNotEnableOrPersistANewSelectionByDefault() = fixture { context, _ ->
        context.resources.getXml(R.xml.pref_sources).use { xml ->
            var found = false
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                val key = xml.getAttributeValue(ANDROID, "key")
                if (key == AppSettings.KEY_SOURCE_CONTENT_CLASSES_ENABLED) {
                    assertEquals("false", xml.getAttributeValue(ANDROID, "defaultValue"))
                    found = true
                } else if (key == AppSettings.KEY_SOURCE_CONTENT_CLASSES) {
                    assertNull(xml.getAttributeValue(ANDROID, "defaultValue"))
                }
            }
            assertTrue(found)
        }
    }

    private fun fixture(block: (Context, SharedPreferences) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "source-content-fixture-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences(prefix + name, mode)
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        try { block(context, prefs) } finally { prefs.edit().clear().commit() }
    }

    companion object { private const val ANDROID = "http://schemas.android.com/apk/res/android" }
}
