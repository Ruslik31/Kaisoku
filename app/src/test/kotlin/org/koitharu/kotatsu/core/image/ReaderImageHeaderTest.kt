package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderImageHeaderTest {

    @Test
    fun recognizesActualJpegRegardlessOfFileNameOrMime() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        assertEquals(ReaderImageHeader.Format.JPEG, ReaderImageHeader.detect(jpeg, jpeg.size))
        assertNull(ReaderImageHeader.detect(jpeg, 2))
    }

    @Test
    fun recognizesAvifMajorOrCompatibleBrandsButNotMinorVersion() {
        val major = box("avif", "xxxx", "mif1")
        assertEquals(ReaderImageHeader.Format.AVIF, ReaderImageHeader.detect(major, major.size))
        val compatible = box("mif1", "xxxx", "avis")
        assertEquals(ReaderImageHeader.Format.AVIF, ReaderImageHeader.detect(compatible, compatible.size))
        val falseBrand = box("mif1", "avif", "heic")
        assertNull(ReaderImageHeader.detect(falseBrand, falseBrand.size))
    }

    @Test
    fun rejectsTruncatedAndMalformedBoxesAndUnrelatedFormats() {
        val avif = box("avif", "xxxx", "mif1")
        assertNull(ReaderImageHeader.detect(avif, avif.size - 1))
        val huge = avif.clone().apply { this[0] = 0xff.toByte() }
        assertNull(ReaderImageHeader.detect(huge, huge.size))
        val odd = avif.clone().apply { this[3] = 19 }
        assertNull(ReaderImageHeader.detect(odd, odd.size))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertNull(ReaderImageHeader.detect(png, png.size))
    }

    private fun box(major: String, minor: String, compatible: String): ByteArray =
        byteArrayOf(0, 0, 0, 20) + "ftyp$major$minor$compatible".toByteArray(Charsets.ISO_8859_1)
}
