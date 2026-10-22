package org.koitharu.kotatsu.image.ui

import coil3.size.Dimension
import coil3.size.Size

internal fun resolveCoverImageSize(
	width: Dimension.Pixels?,
	height: Dimension.Pixels?,
	aspectWidth: Int,
	aspectHeight: Int,
): Size? = when {
	width == null && height == null -> null
	aspectWidth <= 0 || aspectHeight <= 0 -> Size(width ?: Dimension.Undefined, height ?: Dimension.Undefined)
	height == null -> Size(checkNotNull(width), Dimension(width.px * aspectHeight / aspectWidth))
	width == null -> Size(Dimension(height.px * aspectWidth / aspectHeight), height)
	else -> Size(width, height)
}
