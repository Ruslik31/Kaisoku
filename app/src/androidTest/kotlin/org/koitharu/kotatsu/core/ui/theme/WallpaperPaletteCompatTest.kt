package org.koitharu.kotatsu.core.ui.theme

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import androidx.appcompat.R as materialR

@SdkSuppress(minSdkVersion = 30, maxSdkVersion = 30)
@RunWith(AndroidJUnit4::class)
class WallpaperPaletteCompatTest {
    @Test fun lookupsCoalesceAndRespectTheIntervalWithoutPolling() = runTest {
        fixture { context ->
            val calls = AtomicInteger()
            var clock = 0L
            var seed: WallpaperSeed? = WallpaperSeed(1, 0xff208050.toInt())
            val service = WallpaperPaletteCompat(context, { calls.incrementAndGet(); seed }, { clock })
            assertEquals(0, calls.get())
            coroutineScope { List(8) { async { service.refresh() } }.awaitAll() }
            assertEquals(1, calls.get())
            service.refresh()
            assertEquals(1, calls.get())
            clock += 6_000
            seed = WallpaperSeed(2, 0xffd02040.toInt())
            service.refresh()
            assertEquals(seed, service.availableSeed)
            assertEquals(2, calls.get())
            clock += 6_000
            seed = null
            assertEquals(WallpaperPaletteCompat.Status.UNAVAILABLE, service.refresh())
            assertEquals(2, service.availableSeed?.wallpaperId) // Keep the last valid palette after failure.
        }
    }

    @Test fun applyingXmlColorsDoesNotRecolorTheOriginalResources() = runTest {
        fixture { context ->
            val service = WallpaperPaletteCompat(context, { WallpaperSeed(1, 0xff208050.toInt()) }, { 0L })
            service.refresh()
            val original = ContextThemeWrapper(context, R.style.Theme_Kotatsu)
            val before = color(original, materialR.attr.colorPrimary)
            val preview = requireNotNull(service.preview(original))
            assertNotEquals(before, color(preview, materialR.attr.colorPrimary))
            assertEquals(before, color(original, materialR.attr.colorPrimary))
            val isolated = ContextThemeWrapper(WallpaperPaletteCompat.isolateContext(context), R.style.Theme_Kotatsu)
            assertNotNull(service.apply(isolated))
            assertEquals(color(preview, materialR.attr.colorPrimary), color(isolated, materialR.attr.colorPrimary))
            assertEquals(before, color(original, materialR.attr.colorPrimary))
        }
    }

    @Test fun darkAndAmoledUseGeneratedRolesAndKeepBlackBackground() = runTest {
        fixture { context ->
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            val service = WallpaperPaletteCompat(context, { WallpaperSeed(1, 0xff3070d0.toInt()) }, { 0L })
            service.refresh()
            val dark = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_Kotatsu)
            val preview = requireNotNull(service.preview(dark))
            assertEquals(wallpaperScheme(0xff3070d0.toInt(), true).primary, color(preview, materialR.attr.colorPrimary))
            preview.theme.applyStyle(R.style.ThemeOverlay_Kotatsu_Amoled, true)
            assertEquals(0xff000000.toInt(), color(preview, android.R.attr.colorBackground))
        }
    }

    @Test fun missingAndMalformedSeedsUseFallbackWithoutAnOverride() = runTest {
        fixture { context ->
            val prefs = context.getSharedPreferences("_wallpaper_palette", Context.MODE_PRIVATE)
            prefs.edit().putString("seed", "invalid").putInt("wallpaper_id", 1).commit()
            val service = WallpaperPaletteCompat(context, { null }, { 0L })
            val original = ContextThemeWrapper(context, R.style.Theme_Kotatsu)
            assertNull(service.preview(original))
            assertNull(service.apply(original))
            assertEquals(WallpaperPaletteCompat.Status.UNAVAILABLE, service.refresh())
        }
    }

    @Test fun unsupportedOverridesAreNotRetriedOnEveryResumeOrRecreation() = runTest {
        fixture { context ->
            val attempts = AtomicInteger()
            val service = WallpaperPaletteCompat(context, { WallpaperSeed(1, 0xff208050.toInt()) }, { 0L },
                { _, _ -> attempts.incrementAndGet(); false })
            service.refresh()
            assertNull(service.apply(context))
            repeat(4) {
                service.refresh()
                assertNull(service.availableSeed)
                assertNull(service.apply(context))
            }
            assertEquals(1, attempts.get())
            assertEquals(WallpaperPaletteCompat.Status.OVERRIDE_UNAVAILABLE, service.status.value)
            service.refresh(force = true)
            assertNotNull(service.availableSeed)
            assertNull(service.apply(context))
            assertEquals(2, attempts.get())
        }
    }

    private suspend fun fixture(block: suspend (Context) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "wallpaper-fixture-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        try { block(context) } finally {
            context.getSharedPreferences("_wallpaper_palette", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun color(context: Context, attribute: Int): Int =
        context.obtainStyledAttributes(intArrayOf(attribute)).let { values ->
            try { values.getColor(0, 0) } finally { values.recycle() }
        }
}
