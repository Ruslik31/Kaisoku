package org.koitharu.kotatsu.core.parser.mihon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin parts of [MihonExtensionPackageUtil]'s lib-version + NSFW logic. The Bundle-typed
 * `readLibVersion`/`readNsfwFlag` are exercised by instrumentation on-device; here we pin the
 * version window and the string::toVersion rotation used by both.
 */
class MihonExtensionPackageUtilTest {

    @Test
    fun supportedWindowIs14To16() {
        assertTrue(MihonExtensionPackageUtil.isSupportedLibVersion(1.4))
        assertFalse(MihonExtensionPackageUtil.isSupportedLibVersion(1.3))
        // Range includes 1.5 (no extant APK declares it; harmless to accept, excludes stricter checks).
        assertTrue(MihonExtensionPackageUtil.isSupportedLibVersion(1.5))
        assertTrue(MihonExtensionPackageUtil.isSupportedLibVersion(1.6))
        assertFalse(MihonExtensionPackageUtil.isSupportedLibVersion(1.7))
        assertFalse(MihonExtensionPackageUtil.isSupportedLibVersion(2.0))
    }

    @Test
    fun parseLibVersionFromVersionName() {
        assertEquals(1.4, MihonExtensionPackageUtil.parseLibVersion("1.4.7")!!, 0.001)
        assertEquals(1.6, MihonExtensionPackageUtil.parseLibVersion("1.6.0")!!, 0.001)
        assertEquals(1.0, MihonExtensionPackageUtil.parseLibVersion("1")!!, 0.001)
        assertNull(MihonExtensionPackageUtil.parseLibVersion(null))
        assertNull(MihonExtensionPackageUtil.parseLibVersion(""))
        assertNull(MihonExtensionPackageUtil.parseLibVersion("abc"))
    }
}
