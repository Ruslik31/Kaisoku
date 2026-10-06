package org.koitharu.kotatsu.reader.domain

import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.image.CbzFetcher
import org.koitharu.kotatsu.core.util.ext.toZipUri
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AnimatedPageFilesTest {

    @Test
    fun contentDetectionAndExistingDecodersSupportLocalFilesAndCbzEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "pr22-animation-").toFile()
        val loader = ImageLoader.Builder(context).components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory())
            else add(GifDecoder.Factory())
            add(CbzFetcher.Factory())
        }.build()
        try {
            val page = File(directory, "page.bin").apply { writeBytes(ANIMATED_GIF.decodeHex().toByteArray()) }
            val archive = File(directory, "pages.cbz")
            ZipOutputStream(archive.outputStream()).use {
                it.putNextEntry(ZipEntry("page.bin"))
                it.write(page.readBytes())
                it.closeEntry()
            }
            for (uri in listOf(Uri.fromFile(page), archive.toZipUri("page.bin"))) {
                assertTrue(AnimatedImageDetector.isAnimated(uri))
                val result = loader.execute(
                    ImageRequest.Builder(context).data(uri.toString()).size(Size.ORIGINAL).build(),
                )
                assertTrue("Existing Coil decoders must load the original page", result is SuccessResult)
                val drawable = (result as SuccessResult).image.asDrawable(context.resources)
                assertTrue("The decoded page must retain animation", drawable is Animatable)
            }
            assertFalse(AnimatedImageDetector.isAnimated(archive.toZipUri("missing.bin")))
        } finally {
            loader.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun compressedFileLimitUsesAStillFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("pr22-large-", ".gif", context.cacheDir)
        try {
            RandomAccessFile(file, "rw").use {
                it.write(ANIMATED_GIF.decodeHex().toByteArray())
                it.setLength(33L * 1024 * 1024)
            }
            assertFalse(AnimatedImageDetector.isAnimated(Uri.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ANIMATED_GIF = "4749463839610200020081000000000000000000000000000021ff0b4e4554534341" +
            "5045322e30030100000021f90400050000002c00000000020002000008060001080410100021f904010500" +
            "01002c0000000002000200815000000000000000000000000806000108041010003b"
    }
}
