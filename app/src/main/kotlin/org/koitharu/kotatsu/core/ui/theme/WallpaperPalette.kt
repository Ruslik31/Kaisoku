package org.koitharu.kotatsu.core.ui.theme

import android.annotation.SuppressLint
import com.google.android.material.color.MaterialColorUtilitiesHelper
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeTonalSpot

/** At most two fixed-size role maps for the current wallpaper; no bitmap or growing palette cache. */
internal class WallpaperPalette(val seed: WallpaperSeed) {
    private val light by lazy { wallpaperColors(seed.color, false) }
    private val dark by lazy { wallpaperColors(seed.color, true) }

    fun colors(isDark: Boolean): Map<Int, Int> = if (isDark) dark else light
}

internal data class WallpaperSeed(val wallpaperId: Int, val color: Int)

// Pinned Material 1.14.0 already bundles these utilities. No hidden framework APIs are used.
@SuppressLint("RestrictedApi")
internal fun wallpaperScheme(seed: Int, dark: Boolean) = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)

@SuppressLint("RestrictedApi")
private fun wallpaperColors(seed: Int, dark: Boolean): Map<Int, Int> =
    MaterialColorUtilitiesHelper.createColorResourcesIdsToColorValues(wallpaperScheme(seed, dark))
