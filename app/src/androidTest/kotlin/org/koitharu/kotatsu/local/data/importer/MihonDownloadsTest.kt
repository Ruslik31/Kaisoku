package org.koitharu.kotatsu.local.data.importer

import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput.Companion.ENTRY_NAME_INDEX
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MihonDownloadsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mihonRootDownloadsAndSourceSelectionsFindTheSameSeparateTitles() = fixture { root ->
        val first = chapter(root, "downloads/source-a/Title 2/Chapter 1")
        val second = chapter(root, "downloads/source-b/Title 2/Chapter 1")
        val third = chapter(root, "downloads/source-a/Title 10/Chapter 1")
        val expected = listOf(first.parentFile, second.parentFile, third.parentFile).map { it.toURI() }.toSet()
        for (container in listOf(root, File(root, "downloads"))) {
            val result = container.findTitles()
            assertEquals(expected, result.map { File(it.uri.path!!).toURI() }.toSet())
            assertEquals(listOf("Title 2", "Title 2", "Title 10"), result.map { it.name })
        }
        assertEquals(listOf("Title 2", "Title 10"), File(root, "downloads/source-a").findTitles().map { it.name })
        assertTrue(File(first, "ComicInfo.xml").isFile)
        assertTrue(File(second, "000.jpg").isFile)
    }

    @Test
    fun selectingOneTitleOrUnmarkedVolumesKeepsSingleTitleImport() = fixture { root ->
        val marked = chapter(root, "Marked/Chapter 1").parentFile!!
        assertTrue(marked.findTitles().isEmpty())
        File(root, "Plain/Volume 1/Chapter 1").apply { mkdirs(); resolve("000.jpg").writeBytes(byteArrayOf(1)) }
        File(root, "Plain/Volume 2/Chapter 2").apply { mkdirs(); resolve("000.jpg").writeBytes(byteArrayOf(1)) }
        assertTrue(File(root, "Plain").findTitles().isEmpty())
    }

    @Test
    fun discoveryRemainsBoundedToThreeLevels() = fixture { root ->
        chapter(root, "extra/downloads/source/Title/Chapter 1")
        assertTrue(root.findTitles().isEmpty())
        assertEquals(listOf("Title"), File(root, "extra").findTitles().map { it.name })
    }

    @Test
    fun incompleteDownloadsCannotMarkATitleOrBeReturned() = fixture { root ->
        chapter(root, "downloads/source/Incomplete/Chapter 1_tmp")
        chapter(root, "downloads/source/Title_tmp/Chapter 1")
        chapter(root, "downloads/source/Title.tmp/Chapter 1")
        chapter(root, "downloads/source/Complete/Chapter 1")
        assertEquals(listOf("Complete"), root.findTitles().map { it.name })
        assertTrue(File(root, "downloads/source/Incomplete/Chapter 1_tmp/ComicInfo.xml").exists())
    }

    @Test
    fun archivedChaptersNeedComicMetadataToSplitAContainer() = fixture { root ->
        fun archive(path: String, metadata: Boolean) {
            val file = File(root, path).apply { parentFile!!.mkdirs() }
            ZipOutputStream(file.outputStream()).use { zip ->
                if (metadata) {
                    zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zip.write("<ComicInfo><Series>Archived</Series></ComicInfo>".toByteArray())
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("000.jpg"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        archive("downloads/source/Archived/Chapter 1.cbz", metadata = true)
        archive("downloads/source/Unmarked/Chapter 1.cbz", metadata = false)
        archive("downloads/source/Incomplete/Chapter 1.cbz_tmp", metadata = true)
        assertEquals(listOf("Archived"), root.findTitles().map { it.name })
    }

    @Test
    fun stagedMihonIndexUsesFinalDirectoryIdentity() = fixture { root ->
        val staging = File(root, "temporary/Title").apply { mkdirs() }
        chapter(staging, "Chapter 1")
        val finalDirectory = File(root, "Final title")
        assertTrue(writeMihonIndex(staging, finalDirectory))
        val info = MangaIndex(File(staging, ENTRY_NAME_INDEX).readText()).getMangaInfo()!!
        assertEquals(finalDirectory.absolutePath.longHashCode(), info.id)
        assertEquals(finalDirectory.toURI().toString(), info.url)
    }

    private fun chapter(root: File, path: String) = File(root, path).apply {
        mkdirs()
        resolve("ComicInfo.xml").writeText("<ComicInfo><Series>Fixture</Series></ComicInfo>")
        resolve("000.jpg").writeBytes(byteArrayOf(1))
    }

    private fun File.findTitles() = DocumentFile.fromFile(this).findMangaDirs(context.contentResolver)

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "mihon-import-").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively() // Only the unique directory created by this fixture.
        }
    }
}
