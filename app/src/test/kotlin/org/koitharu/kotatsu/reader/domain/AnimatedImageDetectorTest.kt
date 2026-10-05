package org.koitharu.kotatsu.reader.domain

import okio.Buffer
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
