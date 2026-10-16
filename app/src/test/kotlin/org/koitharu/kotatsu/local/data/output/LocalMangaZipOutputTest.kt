package org.koitharu.kotatsu.local.data.output

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LocalMangaZipOutputTest {

	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun cleanupSalvagesOnlyCompletedChapters() = runTest {
		val root = temporaryFolder.newFile("salvage.cbz").also { it.delete() }
		val page = temporaryFolder.newFile("page.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
		val first = chapter(1L, 1f)
		val second = chapter(2L, 2f)
		val output = LocalMangaZipOutput(root, manga(listOf(first, second)))

		output.addPage(IndexedValue(0, first), page, 1, null)
		output.flushChapter(first)
		output.addPage(IndexedValue(1, second), page, 1, null)
		output.cleanup()

		assertTrue(root.isFile)
		ZipFile(root).use { zip ->
			val index = MangaIndex(
				zip.getInputStream(zip.getEntry(LocalMangaOutput.ENTRY_NAME_INDEX)).bufferedReader().use { it.readText() },
			)
			assertEquals(listOf(1L), index.getMangaInfo()?.chapters?.map(MangaChapter::id))
		}
	}

	@Test
	fun cleanupDiscardsArchiveWhenNothingCompleted() = runTest {
		val root = temporaryFolder.newFile("discard.cbz").also { it.delete() }
		val page = temporaryFolder.newFile("incomplete.jpg").also { it.writeBytes(byteArrayOf(1)) }
		val chapter = chapter(1L, 1f)
		val output = LocalMangaZipOutput(root, manga(listOf(chapter)))

		output.addPage(IndexedValue(0, chapter), page, 1, null)
		output.cleanup()

		assertFalse(root.exists())
		assertFalse(temporaryFolder.root.resolve("discard.cbz.tmp").exists())
	}

	@Test
	fun filteringAnUnindexedArchiveReportsAnIoErrorAndPreservesIt() = runTest {
		val file = temporaryFolder.newFile("unindexed.cbz")
		ZipOutputStream(file.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("page.jpg"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
		}
		val original = file.readBytes()
		try {
			LocalMangaZipOutput.filterChapters(file, manga(listOf(chapter(1L, 1f))), setOf(1L))
			throw AssertionError("Expected missing index error")
		} catch (error: IOException) {
			assertTrue(error.message.orEmpty().contains(LocalMangaOutput.ENTRY_NAME_INDEX))
		}
		assertArrayEquals(original, file.readBytes())
		assertFalse(temporaryFolder.root.resolve("unindexed.cbz.tmp").exists())
	}

	@Test
	fun filteringAnInvalidIndexPreservesTheArchive() = runTest {
		val file = temporaryFolder.newFile("invalid-index.cbz")
		ZipOutputStream(file.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry(LocalMangaOutput.ENTRY_NAME_INDEX))
			zip.write("{}".toByteArray())
			zip.closeEntry()
		}
		val original = file.readBytes()
		try {
			LocalMangaZipOutput.filterChapters(file, manga(listOf(chapter(1L, 1f))), setOf(1L))
			throw AssertionError("Expected invalid index error")
		} catch (error: IOException) {
			assertTrue(error.message.orEmpty().contains("No chapters"))
		}
		assertArrayEquals(original, file.readBytes())
	}

	private fun manga(chapters: List<MangaChapter>) = Manga(
		id = 10L,
		title = "Salvage",
		altTitles = emptySet(),
		url = "/salvage",
		publicUrl = "https://example.org/salvage",
		rating = -1f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = MangaParserSource.REMANGA,
		largeCoverUrl = null,
		description = null,
		chapters = chapters,
	)

	private fun chapter(id: Long, number: Float) = MangaChapter(
		id = id,
		title = "Chapter $number",
		number = number,
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MangaParserSource.REMANGA,
	)
}
