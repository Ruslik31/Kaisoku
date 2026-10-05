package org.koitharu.kotatsu.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.core.network.DoHProvider
import org.koitharu.kotatsu.core.network.imageproxy.RealImageProxyInterceptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.parser.lnreader.LNReaderStorage
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.sources.ExtensionLanguageFilter
import java.util.UUID

class RepairPreferencesTest {
    @Test fun mihonSourceSettingsOpenAndPersistWithoutIllegalLegacyPaths() = isolated { context ->
        for (sourceId in listOf("elftoon/3896703921896511395", "ehentai/123")) {
            val source = org.koitharu.kotatsu.core.model.MangaSource("mihon:eu.kanade.tachiyomi.extension.$sourceId")
            val settings = org.koitharu.kotatsu.core.prefs.SourceSettings(context, source)
            settings.isReadingOrderReversed = true
            assertTrue(org.koitharu.kotatsu.core.prefs.SourceSettings(context, source).isReadingOrderReversed)
        }
    }

    @Test fun legacyFileMigrationPreservesCurrentSettings() = isolated { context ->
        val source = org.koitharu.kotatsu.core.model.MangaSource("source:migration-current")
        context.getSharedPreferences(source.name, Context.MODE_PRIVATE).edit()
            .putBoolean("reverse_reading_order", true).putBoolean("slowdown", true).commit()
        context.getSharedPreferences(org.koitharu.kotatsu.core.prefs.SourceSettings.prefsName(source), Context.MODE_PRIVATE)
            .edit().putBoolean("reverse_reading_order", false).commit()
        val settings = org.koitharu.kotatsu.core.prefs.SourceSettings(context, source)
        assertFalse(settings.isReadingOrderReversed)
        assertTrue(settings.isSlowdownEnabled)
        assertTrue(context.getSharedPreferences(source.name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun privateArchiveAlwaysUsesItsActualPath() {
        val info = android.content.pm.ApplicationInfo()
        for (oldPath in listOf(null, "", "/old/location/extension.apk")) {
            info.sourceDir = oldPath
            info.publicSourceDir = oldPath
            with(org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil) {
                info.fixBasePaths("/private/extensions/example.apk")
            }
            assertEquals("/private/extensions/example.apk", info.sourceDir)
            assertEquals(info.sourceDir, info.publicSourceDir)
        }
    }

    @Test fun disabledNetworkChoicesPersistAndOverrideLegacyProxy() = isolated { context ->
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("images_proxy", true).commit()
        val settings = AppSettings(context)
        assertEquals(0, settings.imagesProxy)
        settings.dnsOverHttps = DoHProvider.GOOGLE
        settings.imagesProxy = 0
        settings.dnsOverHttps = DoHProvider.NONE
        settings.imagesProxy = -1
        val reopened = AppSettings(context)
        assertEquals(DoHProvider.NONE, reopened.dnsOverHttps)
        assertEquals(-1, reopened.imagesProxy)
        assertEquals("NONE", prefs.getString(AppSettings.KEY_DOH, null))
        assertEquals("-1", prefs.getString(AppSettings.KEY_IMAGES_PROXY, null))
        assertFalse(prefs.contains("images_proxy"))
    }

    @Test fun imageProxyChangesApplyToTheVeryNextRequestIncludingReset() = isolated { context ->
        runBlocking {
            val settings = AppSettings(context)
            val proxy = RealImageProxyInterceptor(settings)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("image".toResponseBody()).build()
            }.build()
            val request = Request.Builder().url("https://example.org/page.jpg").build()
            settings.imagesProxy = 0
            proxy.interceptPageRequest(request, client).use { assertEquals("wsrv.nl", it.request.url.host) }
            settings.imagesProxy = -1
            proxy.interceptPageRequest(request, client).use { assertEquals(request.url, it.request.url) }
            settings.imagesProxy = 0
            settings.upsertAll(emptyMap<String, Any>())
            proxy.interceptPageRequest(request, client).use { assertEquals(request.url, it.request.url) }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun readingDirectionPersistsPerMangaSourceAndCanBeReset() = isolated { context ->
        val one = org.koitharu.kotatsu.core.model.MangaSource("source:order-one")
        val two = org.koitharu.kotatsu.core.model.MangaSource("source:order-two")
        val settings = org.koitharu.kotatsu.core.prefs.SourceSettings(context, one)
        assertFalse(settings.isReadingOrderReversed)
        settings.isReadingOrderReversed = true
        val reopened = org.koitharu.kotatsu.core.prefs.SourceSettings(context, one)
        assertTrue(reopened.isReadingOrderReversed)
        assertFalse(org.koitharu.kotatsu.core.prefs.SourceSettings(context, two).isReadingOrderReversed)
        reopened.isReadingOrderReversed = false
        assertFalse(org.koitharu.kotatsu.core.prefs.SourceSettings(context, one).isReadingOrderReversed)
    }

    @Test fun chapterListAndSourceReadingDirectionPersistIndependently() = isolated { context ->
        val settings = AppSettings(context)
        assertFalse(settings.isChaptersReverse)
        settings.isChaptersReverse = true
        assertTrue(AppSettings(context).isChaptersReverse)
        val one = org.koitharu.kotatsu.core.model.MangaSource("source:reverse-one")
        val two = org.koitharu.kotatsu.core.model.MangaSource("source:reverse-two")
        val sourceSettings = org.koitharu.kotatsu.core.prefs.SourceSettings(context, one)
        assertFalse(sourceSettings.isReadingOrderReversed)
        sourceSettings.isReadingOrderReversed = false
        assertFalse(org.koitharu.kotatsu.core.prefs.SourceSettings(context, one).isReadingOrderReversed)
        assertFalse(org.koitharu.kotatsu.core.prefs.SourceSettings(context, two).isReadingOrderReversed)
        assertTrue(AppSettings(context).isChaptersReverse)
        settings.isChaptersReverse = false
        assertFalse(AppSettings(context).isChaptersReverse)
    }

    @Test fun legacyReadingDirectionsMigrateWithoutOverwritingNewSelections() = isolated { context ->
        val novel = org.koitharu.kotatsu.core.model.MangaSource("lnreader:reverse-migration")
        val manga = org.koitharu.kotatsu.core.model.MangaSource("source:reverse-migration-manga")
        for (source in listOf(novel, manga)) {
            val prefs = context.getSharedPreferences(
                org.koitharu.kotatsu.core.prefs.SourceSettings.prefsName(source), Context.MODE_PRIVATE,
            )
            prefs.edit().putBoolean("chapters_reverse_override", true).commit()
            if (source == novel) prefs.edit().putBoolean("novel_reverse_reading", false).commit()
            val migrated = org.koitharu.kotatsu.core.prefs.SourceSettings(context, source)
            assertEquals(source != novel, migrated.isReadingOrderReversed)
            migrated.isReadingOrderReversed = source == novel
            assertEquals(source == novel, org.koitharu.kotatsu.core.prefs.SourceSettings(context, source).isReadingOrderReversed)
        }
    }

    @Test fun extensionApkChooserEntryCanBeDisabledAndRestored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val component = ComponentName(
            context,
            "org.koitharu.kotatsu.settings.sources.PluginApkActivityAlias",
        )
        val query = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://org.example.extension/file.apk"), "application/vnd.android.package-archive")
        fun hasKaisokuHandler(): Boolean = packageManager.queryIntentActivities(query, 0)
            .any {
                it.activityInfo.packageName == context.packageName &&
					(it.activityInfo.name == component.className ||
						it.activityInfo.targetActivity == "org.koitharu.kotatsu.settings.sources.PluginActivity")
			}

        try {
            packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            assertTrue(hasKaisokuHandler())
            packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            assertFalse(hasKaisokuHandler())
        } finally {
            packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }

    private inline fun isolated(block: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "repair-test-" + UUID.randomUUID()
        val names = HashSet<String>()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                names.add(isolatedName)
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        try { block(context) } finally { names.forEach(app::deleteSharedPreferences) }
    }

    @Test fun languageSelectionsPersistSeparatelyAcrossRecreation() = isolated { context ->
        val mihon = ExtensionLanguageFilter(context, "mihon")
        val novels = ExtensionLanguageFilter(context, "lnreader")
        assertTrue(mihon.selected.value.isEmpty())
        assertTrue(novels.selected.value.isEmpty())
        mihon.select(setOf("Русский", "EN"))
        novels.select(setOf("Japanese"))
        assertEquals(setOf("ru", "en"), ExtensionLanguageFilter(context, "mihon").selected.value)
        assertEquals(setOf("ja"), ExtensionLanguageFilter(context, "lnreader").selected.value)
        mihon.select(emptySet())
        assertTrue(ExtensionLanguageFilter(context, "mihon").selected.value.isEmpty())
        assertEquals(setOf("ja"), ExtensionLanguageFilter(context, "lnreader").selected.value)
    }

    @Test fun upscalerConfigurationPersistsAndClampsOutOfRangeValues() = isolated { context ->
        val settings = AppSettings(context)
        assertEquals(75, settings.readerUpscaleStrength)
        assertEquals(0, settings.readerUpscalePasses)
        assertEquals(1.5f, settings.readerUpscaleThreshold, 0f)
        settings.isReaderUpscaleEnabled = true
        settings.readerUpscaleStrength = 50
        settings.readerUpscalePasses = 3
        settings.readerUpscaleThreshold = 2f
        val restored = AppSettings(context)
        assertTrue(restored.isReaderUpscaleEnabled)
        assertEquals(50, restored.readerUpscaleStrength)
        assertEquals(3, restored.readerUpscalePasses)
        assertEquals(2f, restored.readerUpscaleThreshold, 0f)
        restored.readerUpscaleStrength = 150
        restored.readerUpscalePasses = -1
        assertEquals(100, restored.readerUpscaleStrength)
        assertEquals(0, restored.readerUpscalePasses)
    }

    @Test fun pluginStoragePersistsWithoutCrossPluginValues() = isolated { context ->
        val one = context.getSharedPreferences("plugin-one", Context.MODE_PRIVATE)
        val two = context.getSharedPreferences("plugin-two", Context.MODE_PRIVATE)
        LNReaderStorage(one).set("plugin:book", "{\"id\":7}")
        assertEquals("{\"id\":7}", LNReaderStorage(one).get("plugin:book"))
        assertNull(LNReaderStorage(two).get("plugin:book"))
        LNReaderStorage(one).set("plugin:book", null)
        assertTrue(LNReaderStorage(one).keys().isEmpty())
    }

    @Test fun restoredIntegerUpscaleThresholdMigratesWithoutReaderCrash() = isolated { context ->
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val settings = AppSettings(context)
        for (value in listOf(1, 2, 3)) {
            settings.upsertAll(mapOf(AppSettings.KEY_UPSCALE_THRESHOLD to value), isMerge = true)
            assertEquals(value.toFloat(), settings.readerUpscaleConfig.threshold, 0f)
            assertEquals(value.toFloat(), prefs.getFloat(AppSettings.KEY_UPSCALE_THRESHOLD, 0f), 0f)
            assertEquals(value.toFloat(), AppSettings(context).readerUpscaleThreshold, 0f)
        }
        prefs.edit().putString(AppSettings.KEY_UPSCALE_THRESHOLD, "1.5").commit()
        assertEquals(1.5f, settings.readerUpscaleThreshold, 0f)
        prefs.edit().putString(AppSettings.KEY_UPSCALE_THRESHOLD, "invalid").commit()
        assertEquals(1.5f, settings.readerUpscaleThreshold, 0f)
    }

    @Test fun fractionalUpscaleThresholdSurvivesJsonSettingsRestore() = isolated { context ->
        val settings = AppSettings(context)
        val jsonValue = org.json.JSONObject("{\"reader_upscale_threshold\":1.5}")
            .get(AppSettings.KEY_UPSCALE_THRESHOLD)
        assertTrue(jsonValue is Double)
        settings.upsertAll(mapOf(AppSettings.KEY_UPSCALE_THRESHOLD to jsonValue))
        assertEquals(1.5f, settings.readerUpscaleThreshold, 0f)
        assertTrue(settings.getAllValues()[AppSettings.KEY_UPSCALE_THRESHOLD] is Float)
    }
}
