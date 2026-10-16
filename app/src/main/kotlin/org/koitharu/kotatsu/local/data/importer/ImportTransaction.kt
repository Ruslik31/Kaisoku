package org.koitharu.kotatsu.local.data.importer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Owns only new temporary/output files. Existing imports are never overwritten or merged accidentally. */
internal object ImportTransaction {
    private val publishing = Mutex()
    suspend fun <T> run(output: File, name: String, build: suspend (File) -> File, parse: suspend (File) -> T,
                        preparePublish: suspend (File, File) -> Unit = { _, _ -> }): T {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        val staging = File(output, ".import-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Cannot create import staging directory")
        var committed: File? = null
        try {
            val prepared = build(staging)
            require(prepared.parentFile == staging && prepared.exists())
            parse(prepared) // Reject invalid titles before making them visible.
            currentCoroutineContext().ensureActive()
            return publishing.withLock {
                val target = uniqueDestination(output, name)
                preparePublish(prepared, target)
                currentCoroutineContext().ensureActive()
                // No REPLACE_EXISTING: another writer may claim the name after our existence check.
                Files.move(prepared.toPath(), target.toPath())
                committed = target
                parse(target).also { committed = null }
            }
        } finally {
            // Both paths were exclusively created by this operation.
            committed?.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    internal fun uniqueDestination(output: File, name: String): File {
        val original = File(output, name)
        if (!original.exists()) return original
        val extension = original.extension.takeIf { original.isFile && it.isNotEmpty() }
        val stem = if (extension == null) name else name.substringBeforeLast('.')
        var index = 1
        while (true) {
            val candidate = File(output, "${stem}_$index" + (extension?.let { ".$it" } ?: ""))
            if (!candidate.exists()) return candidate
            index++
        }
    }
}

internal data class ImportFailure(val title: String, val error: Throwable)
internal data class MangaImportResult<T>(val imported: List<T>, val failures: List<ImportFailure>)

/** Keep successes immediately; cancellation remains cancellation instead of becoming a failed-title entry. */
internal suspend fun <T, R> importBatch(items: List<T>, title: (T) -> String, import: suspend (T) -> R,
                                     published: suspend (R) -> Unit): MangaImportResult<R> {
    val imported = ArrayList<R>()
    val failures = ArrayList<ImportFailure>()
    for (item in items) {
        currentCoroutineContext().ensureActive()
        val result = try { import(item) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failures.add(ImportFailure(title(item), e)); continue }
        imported.add(result)
        published(result)
    }
    return MangaImportResult(imported, failures)
}
