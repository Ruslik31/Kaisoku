package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.core.model.PluginMangaSource
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Retain the owning JAR of requests still running on a previous plugin class loader. */
internal class PluginSourceLookup {
    private var current = IdentityHashMap<Any, PluginMangaSource>()
    private val previous = WeakHashMap<Any, WeakReference<PluginMangaSource>>()

    @Synchronized
    fun replace(sources: List<PluginMangaSource>) {
        current.forEach { (delegate, source) -> previous[delegate] = WeakReference(source) }
        current = sources.associateByTo(IdentityHashMap()) { it.delegate }
    }

    @Synchronized
    fun find(delegate: Any): PluginMangaSource? = current[delegate] ?: previous[delegate]?.get()

    @Synchronized
    fun sources(): List<PluginMangaSource> = current.values.toList()
}
