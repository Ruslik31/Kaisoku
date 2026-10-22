package org.koitharu.kotatsu.core.parser.mihon

import dalvik.system.PathClassLoader

class ChildFirstPathClassLoader(
	dexPath: String,
	librarySearchPath: String?,
	parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

	private val parentPackages = setOf(
		"java.",
		"javax.",
		"kotlin.",
		"kotlinx.",
		"android.",
		"androidx.",
		"org.json.",
		"org.jsoup.",
		"okhttp3.",
		"okio.",
		"rx.",
		"eu.kanade.tachiyomi.source.",
		"eu.kanade.tachiyomi.network.",
		"eu.kanade.tachiyomi.util.",
		"uy.kohesive.injekt.",
	)

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		if (parentPackages.any(name::startsWith)) {
            return try {
                parent.loadClass(name)
            } catch (e: ClassNotFoundException) {
                // Optional novel wrappers carry subclasses absent from the host. Always prefer
                // host ABI classes, but allow an extension-owned addition to that namespace.
                if (!allowsOptionalExtensionClass(name)) throw e
                findLoadedClass(name) ?: findClass(name)
            }
		}
		return try {
			findLoadedClass(name) ?: findClass(name)
		} catch (_: ClassNotFoundException) {
			parent.loadClass(name)
		}
	}
}

/** Optional runtime additions confirmed in the published novel-wrapper APKs. */
internal fun allowsOptionalExtensionClass(name: String): Boolean =
    name.startsWith("eu.kanade.tachiyomi.source.online.") ||
        name.startsWith("kotlinx.datetime.") || name.startsWith("kotlinx.io.")
