package org.koitharu.kotatsu.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource

class SourceSettingsNamesTest {
    @Test
    fun mihonMigrationNeverOpensAnIllegalAndroidPreferenceFilename() {
        for (name in listOf(
            "mihon:eu.kanade.tachiyomi.extension.en.elftoon/3896703921896511395",
            "mihon:eu.kanade.tachiyomi.extension.all.ehentai/123",
        )) {
            val source = MangaSource(name)
            assertEquals(name.substringAfter(':').replace('/', '$'), SourceSettings.prefsName(source))
            assertFalse(SourceSettings.legacyPrefsNames(source).any { '/' in it })
            assertEquals(listOf("plugin.jar:${SourceSettings.prefsName(source)}"), SourceSettings.legacyPrefsNames(source))
        }
    }

    @Test
    fun existingNativeAndNovelPreferenceNamesRemainCompatible() {
        assertEquals("READMANGA", SourceSettings.prefsName(MangaSource("READMANGA")))
        assertEquals(listOf("plugin.jar:READMANGA"), SourceSettings.legacyPrefsNames(MangaSource("READMANGA")))
        assertEquals("novelbin", SourceSettings.prefsName(MangaSource("lnreader:novelbin")))
        assertEquals(listOf("lnreader:novelbin", "plugin.jar:novelbin"),
            SourceSettings.legacyPrefsNames(MangaSource("lnreader:novelbin")))
    }
}
