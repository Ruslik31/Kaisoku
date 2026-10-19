package org.koitharu.kotatsu.core.ui.theme

import android.annotation.SuppressLint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@SuppressLint("RestrictedApi") // Tests for the already bundled, pinned Material color utilities.
class WallpaperPaletteTest {
    private val seeds = listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffd02040.toInt(),
        0xff208050.toInt(), 0xff3070d0.toInt(), 0xff9040c0.toInt())

    @Test fun seedsProduceStableOpaqueDayAndNightRolesWithReadableText() {
        for (seed in seeds) for (dark in listOf(false, true)) {
            val scheme = wallpaperScheme(seed, dark)
            assertEquals(scheme.primary, wallpaperScheme(seed, dark).primary)
            for (color in listOf(scheme.primary, scheme.onPrimary, scheme.surface, scheme.onSurface)) {
                assertEquals(255, color ushr 24)
            }
            assertTrue("primary contrast", contrast(scheme.primary, scheme.onPrimary) >= 4.5)
            assertTrue("surface contrast", contrast(scheme.surface, scheme.onSurface) >= 7.0)
        }
    }

    @Test fun palettesActuallyFollowTheSeedAndTheNightMode() {
        assertNotEquals(wallpaperScheme(seeds[2], false).primary, wallpaperScheme(seeds[3], false).primary)
        assertNotEquals(wallpaperScheme(seeds[2], false).surface, wallpaperScheme(seeds[2], true).surface)
    }

    @Test fun currentPaletteRetainsOnlyOneMapPerMode() {
        val palette = WallpaperPalette(WallpaperSeed(10, seeds[2]))
        assertSame(palette.colors(false), palette.colors(false))
        assertSame(palette.colors(true), palette.colors(true))
        assertEquals(palette.colors(false).keys, palette.colors(true).keys)
        assertTrue(palette.colors(false).size in 20..100)
        assertNotEquals(palette.colors(false), palette.colors(true))
    }

    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }

    private fun luminance(color: Int): Double = listOf(16, 8, 0).map { shift ->
        val channel = ((color ushr shift) and 255) / 255.0
        if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
    }.let { it[0] * .2126 + it[1] * .7152 + it[2] * .0722 }
}
