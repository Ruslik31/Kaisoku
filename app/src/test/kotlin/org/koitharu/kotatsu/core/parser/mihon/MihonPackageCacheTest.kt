package org.koitharu.kotatsu.core.parser.mihon

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MihonPackageCacheTest {
    private val identity = MihonPackageIdentity(
        "extension.test", "/private/extension.apk", true, 1, "1.6.0", ".Test", null,
        1.6, false, listOf("trusted"), "content-one",
    )

    @Test fun unchangedLoadsReuseTheSameSessionButAllValidationFieldsMatter() {
        val cache = MihonPackageCache<Any>()
        val session = Any()
        cache.put(identity, session)
        assertSame(session, cache.get(identity.copy()))
        for (changed in listOf(
            identity.copy(apkDigest = "content-two"),
            identity.copy(isPrivate = false),
            identity.copy(apkPath = "/android/new.apk"),
            identity.copy(versionCode = 2),
            identity.copy(versionName = "1.6.1"),
            identity.copy(entryClass = ".Other"),
            identity.copy(nativeLibraryPath = "/new/lib"),
            identity.copy(libVersion = 1.4),
            identity.copy(isNsfw = true),
            identity.copy(signatures = listOf("untrusted")),
        )) assertNull(cache.get(changed))
    }

    @Test fun sharedAndPrivatePackagesArePrunedTogetherAndFailedValidationEvicts() {
        val cache = MihonPackageCache<String>()
        val shared = identity.copy(packageName = "extension.shared", isPrivate = false)
        cache.put(identity, "private")
        cache.put(shared, "shared")
        cache.retainPackages(setOf(identity.packageName, shared.packageName))
        assertEquals("private", cache.get(identity))
        assertEquals("shared", cache.get(shared))
        cache.retainPackages(setOf(identity.packageName))
        assertNull(cache.get(shared))
        cache.remove(identity.packageName)
        assertNull(cache.get(identity))
    }

    @Test fun samePathVersionLengthAndTimestampReplacementChangesFingerprint() {
        val file = File.createTempFile("mihon-apk-test", ".apk")
        try {
            file.writeText("first-apk")
            val timestamp = file.lastModified()
            val first = MihonPackageCache.fingerprint(file)
            file.writeText("other-apk")
            assertTrue(file.setLastModified(timestamp))
            assertEquals(9L, file.length())
            assertNotEquals(first, MihonPackageCache.fingerprint(file))
        } finally {
            file.delete()
        }
    }

    @Test fun fingerprintPreservesInterruption() {
        val file = File.createTempFile("mihon-apk-test", ".apk")
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { MihonPackageCache.fingerprint(file) }
        } finally {
            Thread.interrupted()
            file.delete()
        }
    }
}
