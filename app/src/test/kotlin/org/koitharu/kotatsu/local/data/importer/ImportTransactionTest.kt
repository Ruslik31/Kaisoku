package org.koitharu.kotatsu.local.data.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImportTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun failedConversionAndValidationLeaveExistingTitleUntouched() = runTest {
        val root = temporary.root
        val original = File(root, "Title").apply { mkdir(); File(this, "old.jpg").writeText("keep") }
        for (failure in listOf("copy", "parse", "final parse", "cancel")) {
            var parsed = 0
            try {
                ImportTransaction.run(root, "Title", { stage ->
                    val file = File(stage, "Title").apply { mkdir(); File(this, "new.jpg").writeText("new") }
                    if (failure == "copy") throw IOException("broken input")
                    if (failure == "cancel") throw CancellationException("cancelled")
                    file
                }, { file ->
                    parsed++
                    if (failure == "parse" || (failure == "final parse" && parsed == 2)) throw IOException("bad title")
                    file
                })
                fail("Expected $failure")
            } catch (_: IOException) { } catch (_: CancellationException) { }
            assertEquals(listOf("Title"), root.list().orEmpty().toList())
            assertEquals("keep", File(original, "old.jpg").readText())
        }
    }
    @Test fun successfulReimportGetsSeparatePathAndNeverOverwritesArchive() = runTest {
        val root = temporary.root
        File(root, "Title.cbz").writeText("old")
        val result = ImportTransaction.run(root, "Title.cbz", { stage -> File(stage, "Title.cbz").apply { writeText("new") } }, { it })
        assertEquals("Title_1.cbz", result.name)
        assertEquals("new", result.readText())
        assertEquals("old", File(root, "Title.cbz").readText())
        assertEquals(2, root.list().orEmpty().size)
    }
    @Test fun competingWriterCannotHaveItsFileOverwrittenDuringPublication() = runTest {
        val root = temporary.root
        try {
            ImportTransaction.run(root, "Title.cbz", { stage -> File(stage, "Title.cbz").apply { writeText("new import") } },
                { it }, preparePublish = { _, target -> target.writeText("other operation") })
            fail("Competing output must be retained")
        } catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("other operation", File(root, "Title.cbz").readText())
        assertEquals(listOf("Title.cbz"), root.list().orEmpty().toList())
    }
    @Test fun stagedMetadataUsesChosenDestinationBeforePublishing() = runTest {
        val root = temporary.root
        File(root, "Title").mkdir()
        val result = ImportTransaction.run(root, "Title", { stage -> File(stage, "Title").apply { mkdir() } },
            { it }, preparePublish = { staged, target ->
                assertFalse(target.exists())
                File(staged, "identity").writeText(target.absolutePath)
            })
        assertEquals("Title_1", result.name)
        assertEquals(result.absolutePath, File(result, "identity").readText())
    }
    @Test fun partialBatchContinuesAndPublishesEachSuccessOnce() = runTest {
        val published = ArrayList<Int>()
        val result = importBatch(listOf(1, 2, 3), { "Title $it" }, { if (it == 2) throw IOException("bad PDF") else it }, { published.add(it) })
        assertEquals(listOf(1, 3), result.imported)
        assertEquals(listOf(1, 3), published)
        assertEquals("Title 2", result.failures.single().title)
    }
    @Test fun cancellationStopsBatchWithoutDiscardingEarlierSuccess() = runTest {
        val published = ArrayList<Int>()
        try {
            importBatch(listOf(1, 2, 3), { "$it" }, { if (it == 2) throw CancellationException("cancel") else it }, { published.add(it) })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(1), published)
    }
    @Test fun pdfRasterMemoryIsBoundedForTallWideAndHugeDocuments() {
        for ((width, height) in listOf(600 to 800, 100 to 100_000, 100_000 to 100, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val (w, h) = pdfPageSize(width, height)
            assertTrue(w > 0 && h > 0 && w <= 16_384 && h <= 16_384)
            assertTrue(w.toLong() * h <= 4_000_000)
            assertEquals(width.toDouble() / height, w.toDouble() / h, maxOf(0.02, width.toDouble() / height * 0.2))
        }
    }
}
