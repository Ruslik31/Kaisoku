package org.koitharu.kotatsu.core.parser.mihon

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.model.SourceContentClass

@RunWith(AndroidJUnit4::class)
class MihonContentClassTest {
    @Test fun manifestBundleTypesKeepMixedDistinctAndOlderFlagsRestricted() {
        val bundle = Bundle()
        for (warning in listOf(0, 1, 2)) {
            bundle.putInt(MihonExtensionPackageUtil.METADATA_CONTENT_WARNING, warning)
            assertEquals(SourceContentClass.entries[warning], MihonExtensionPackageUtil.readSourceContentClass(bundle))
            assertEquals(warning > 0, MihonExtensionPackageUtil.readNsfwFlag(bundle))
        }
        bundle.putString(MihonExtensionPackageUtil.METADATA_CONTENT_WARNING, "1")
        bundle.putBoolean("tachiyomi.extension.nsfw", true)
        assertEquals(SourceContentClass.MIXED, MihonExtensionPackageUtil.readSourceContentClass(bundle))
        assertTrue(MihonExtensionPackageUtil.readNsfwFlag(bundle))
        bundle.putInt(MihonExtensionPackageUtil.METADATA_CONTENT_WARNING, 0)
        assertEquals(SourceContentClass.ADULT, MihonExtensionPackageUtil.readSourceContentClass(bundle))
        bundle.putString(MihonExtensionPackageUtil.METADATA_CONTENT_WARNING, "3")
        assertEquals(SourceContentClass.ADULT, MihonExtensionPackageUtil.readSourceContentClass(bundle))
    }

    @Test fun refreshedWrapperCanCarryClassChangesWithoutChangingSourceIdentity() {
        val safe = MihonMangaSource("fixture.content", 728, contentClass = SourceContentClass.SAFE)
        val mixed = MihonMangaSource("fixture.content", 728, isNsfwSource = true, contentClass = SourceContentClass.MIXED)
        assertEquals(safe.name, mixed.name)
        assertEquals(safe, mixed)
        assertEquals(SourceContentClass.MIXED, mixed.contentClass)
        assertEquals(SourceContentClass.SAFE, safe.contentClass)
    }
}
