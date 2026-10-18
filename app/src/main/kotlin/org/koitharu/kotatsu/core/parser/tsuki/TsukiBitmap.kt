package org.koitharu.kotatsu.core.parser.tsuki

import org.koitharu.kotatsu.core.parser.BitmapWrapper
import org.koitharu.kotatsu.parsers.bitmap.Rect
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect as TRect

/** Exposes a host [BitmapWrapper] through the tsuki.* bitmap API used for image descrambling. */
class TsukiBitmap(
	val wrapper: BitmapWrapper,
) : Bitmap {

	override val width: Int
		get() = wrapper.width

	override val height: Int
		get() = wrapper.height

	override fun drawBitmap(sourceBitmap: Bitmap, src: TRect, dst: TRect) {
		wrapper.drawBitmap((sourceBitmap as TsukiBitmap).wrapper, src.toHost(), dst.toHost())
	}

	private fun TRect.toHost() = Rect(left = left, top = top, right = right, bottom = bottom)
}
