package org.koitharu.kotatsu.core.parser.mihon

import java.io.File
import java.security.MessageDigest

internal data class MihonPackageIdentity(
    val packageName: String,
    val apkPath: String,
    val isPrivate: Boolean,
    val versionCode: Long,
    val versionName: String?,
    val entryClass: String,
    val nativeLibraryPath: String?,
    val libVersion: Double?,
    val isNsfw: Boolean,
    val signatures: List<String>,
    val apkDigest: String,
)

/** Only successful loads are stored; the caller validates current package metadata before lookup. */
internal class MihonPackageCache<T> {
    private val entries = HashMap<String, Pair<MihonPackageIdentity, T>>()

    fun get(identity: MihonPackageIdentity): T? = entries[identity.packageName]
        ?.takeIf { it.first == identity }?.second

    fun put(identity: MihonPackageIdentity, value: T) {
        entries[identity.packageName] = identity to value
    }

    fun retainPackages(names: Set<String>) {
        entries.keys.retainAll(names)
    }

    fun remove(packageName: String) {
        entries.remove(packageName)
    }

    fun contains(packageName: String): Boolean = entries.containsKey(packageName)

    companion object {
        fun fingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
