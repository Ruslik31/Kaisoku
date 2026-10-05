package org.koitharu.kotatsu.reader.domain

import android.net.Uri
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.net.toFile
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.buffer
import okio.source
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.util.zip.ZipFile

/**
 * Detects animated GIF and animated WebP pages by their content, not by url.
 * Anything that is not recognized (including read errors) is reported as static,
 * so such pages keep using the regular page view.
 */
object AnimatedImageDetector {

	// Very large animations are left to the regular page view (first frame only)
	private const val MAX_FILE_SIZE = 64L * 1024 * 1024

	private val GIF87A = "GIF87a".encodeUtf8()
	private val GIF89A = "GIF89a".encodeUtf8()
	private val RIFF = "RIFF".encodeUtf8()
	private val WEBP = "WEBP".encodeUtf8()
	private val VP8X = "VP8X".encodeUtf8()

	@WorkerThread
	fun isAnimated(uri: Uri): Boolean = try {
		val isWebpSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
		when {
			uri.isZipUri() -> ZipFile(uri.schemeSpecificPart).use { zip ->
				val entry = uri.fragment?.let { zip.getEntry(it) }
				if (entry == null || entry.size > MAX_FILE_SIZE) {
					false
				} else {
					zip.getInputStream(entry).source().buffer().use { isAnimated(it, isWebpSupported) }
				}
			}

			uri.isFileUri() -> {
				val file = uri.toFile()
				if (file.length() > MAX_FILE_SIZE) {
					false
				} else {
					file.source().buffer().use { isAnimated(it, isWebpSupported) }
				}
			}

			else -> false
		}
	} catch (e: Exception) {
		e.printStackTraceDebug()
		false
	}

	fun isAnimated(source: BufferedSource, isWebpSupported: Boolean): Boolean = try {
		when {
			source.rangeEquals(0, GIF89A) || source.rangeEquals(0, GIF87A) -> hasMultipleGifFrames(source)
			isWebpSupported && source.rangeEquals(0, RIFF) && source.rangeEquals(8, WEBP) &&
				source.rangeEquals(12, VP8X) -> {
				// VP8X flags byte, bit 1 is the animation flag
				source.request(21) && (source.buffer[20].toInt() and 0x02) != 0
			}

			else -> false
		}
	} catch (e: IOException) {
		false
	}

	private fun hasMultipleGifFrames(source: BufferedSource): Boolean {
		source.skip(10) // signature, logical screen width and height
		val screenFlags = source.readByte().toInt() and 0xFF
		source.skip(2) // background color index, pixel aspect ratio
		if (screenFlags and 0x80 != 0) {
			source.skip(colorTableSize(screenFlags))
		}
		var frames = 0
		while (true) {
			when (source.readByte().toInt() and 0xFF) {
				0x21 -> { // extension: label, then data sub-blocks
					source.skip(1)
					skipSubBlocks(source)
				}

				0x2C -> { // image descriptor
					if (++frames > 1) {
						return true
					}
					source.skip(8) // position and size
					val imageFlags = source.readByte().toInt() and 0xFF
					if (imageFlags and 0x80 != 0) {
						source.skip(colorTableSize(imageFlags))
					}
					source.skip(1) // LZW minimum code size
					skipSubBlocks(source)
				}

				else -> return false // trailer or unknown data
			}
		}
	}

	private fun colorTableSize(flags: Int): Long = 3L * (1 shl ((flags and 0x07) + 1))

	private fun skipSubBlocks(source: BufferedSource) {
		while (true) {
			val size = source.readByte().toInt() and 0xFF
			if (size == 0) {
				return
			}
			source.skip(size.toLong())
		}
	}
}
