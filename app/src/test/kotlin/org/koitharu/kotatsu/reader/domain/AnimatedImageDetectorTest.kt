package org.koitharu.kotatsu.reader.domain

import okio.Buffer
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException

class AnimatedImageDetectorTest {

    @Test
    fun staticGifIsNotAnimated() {
        assertFalse(detect(STATIC_GIF))
    }

    @Test
    fun gifWithTwoFramesIsAnimated() {
        assertTrue(detect(ANIMATED_GIF))
    }

    @Test
    fun truncatedGifIsNotAnimated() {
        assertFalse(detect(ANIMATED_GIF.substring(0, 80)))
    }

    @Test
    fun animatedWebpIsDetectedOnlyWhenSupported() {
        assertTrue(detect(webpVp8x(flags = 0x02)))
        assertFalse(detect(webpVp8x(flags = 0x02), isWebpSupported = false))
    }

    @Test
    fun staticWebpIsNotAnimated() {
        assertFalse(detect(webpVp8x(flags = 0x10)))
        assertFalse(detect("52494646500000005745425056503820" + "00".repeat(16)))
    }

    @Test
    fun otherFormatsAreNotAnimated() {
        assertFalse(detect("89504e470d0a1a0a0000000d49484452"))
        assertFalse(detect("ffd8ffe000104a464946000101000001"))
        assertFalse(detect(""))
    }

    @Test
    fun oversizedAndZeroGifCanvasesRemainStatic() {
        val bytes = ANIMATED_GIF.decodeHex().toByteArray()
        bytes[6] = 0
        bytes[7] = 0
        assertFalse(detect(bytes))
        bytes[6] = 0xFF.toByte()
        bytes[7] = 0x7F
        bytes[8] = 0xFF.toByte()
        bytes[9] = 0x7F
        assertFalse(detect(bytes))
    }

    @Test
    fun gifFramesMustStayInsideTheirCanvas() {
        val bytes = ANIMATED_GIF.decodeHex().toByteArray()
        val secondFrame = bytes.indices.last { bytes[it] == 0x2C.toByte() }
        bytes[secondFrame + 1] = 2 // left=2, frame width=2, canvas width=2
        assertFalse(detect(bytes))
    }

    @Test
    fun truncatedSecondGifFrameDoesNotCountAsAnimation() {
        val bytes = ANIMATED_GIF.decodeHex().toByteArray()
        val secondFrame = bytes.indices.last { bytes[it] == 0x2C.toByte() }
        assertFalse(detect(bytes.copyOf(secondFrame + 1)))
        assertFalse(detect(bytes.copyOf(bytes.size - 1))) // missing trailer
    }

    @Test
    fun manyGifFramesUseAStillFrame() {
        val header = "47494638396101000100000000"
        val frame = "2c000000000100010080000000ffffff0202440100"
        assertTrue(detect(header + frame.repeat(128) + "3b"))
        assertFalse(detect(header + frame.repeat(129) + "3b"))
    }

    @Test
    fun gifFrameBudgetAccountsForTheWholeCanvas() {
        val header = "47494638396100080008000000" // 2048 x 2048
        val frame = "2c000000000100010080000000ffffff0202440100"
        assertTrue(detect(header + frame.repeat(8) + "3b"))
        assertFalse(detect(header + frame.repeat(9) + "3b"))
    }

    @Test
    fun webpCanvasDimensionsAreUnsignedAndBounded() {
        val bytes = webpVp8x(flags = 0x02).decodeHex().toByteArray()
        // VP8X stores dimensions minus one, as little-endian unsigned 24-bit values.
        bytes[24] = 0xFF.toByte()
        bytes[25] = 0xFF.toByte()
        bytes[26] = 0xFF.toByte()
        assertFalse(detect(bytes))
        assertFalse(detect(bytes.copyOf(29)))
        bytes[24] = 0xFF.toByte()
        bytes[25] = 0x03
        bytes[26] = 0
        assertTrue(detect(bytes)) // 1024 x 1
    }

    @Test
    fun cancellationIsPropagatedInsteadOfBeingClassifiedAsStatic() {
        Thread.currentThread().interrupt()
        try {
            AnimatedImageDetector.isAnimated(Buffer().write(ANIMATED_GIF.decodeHex()), true)
            throw AssertionError("Interrupted scan must be cancelled")
        } catch (_: InterruptedIOException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun detect(bytes: ByteArray): Boolean = AnimatedImageDetector.isAnimated(Buffer().write(bytes), true)

    private fun detect(hex: String, isWebpSupported: Boolean = true): Boolean {
        val buffer = Buffer().write(hex.decodeHex())
        return AnimatedImageDetector.isAnimated(buffer, isWebpSupported)
    }

    private fun webpVp8x(flags: Int): String {
        // RIFF header, VP8X chunk header, flags, reserved bytes and canvas size
        return "52494646" + "2a000000" + "57454250" + "56503858" + "0a000000" +
            "%02x".format(flags) + "000000" + "000000" + "000000"
    }

    private companion object {

        const val STATIC_GIF = "474946383761020002008100000000000000000000000000002c000000000200020000" +
            "0806000108041010003b"

        const val ANIMATED_GIF = "4749463839610200020081000000000000000000000000000021ff0b4e4554534341" +
            "5045322e30030100000021f90400050000002c00000000020002000008060001080410100021f904010500" +
            "01002c0000000002000200815000000000000000000000000806000108041010003b"
    }
}
