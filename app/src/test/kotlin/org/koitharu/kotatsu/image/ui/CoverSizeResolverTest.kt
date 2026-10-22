package org.koitharu.kotatsu.image.ui

import coil3.size.Dimension
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverSizeResolverTest {

	@Test
	fun missingHeightWithoutAnAspectRatioUsesItsIntrinsicSize() {
		assertEquals(
			Size(Dimension(300), Dimension.Undefined),
			resolveCoverImageSize(Dimension(300), null, 0, 0),
		)
	}

	@Test
	fun missingWidthWithoutAnAspectRatioUsesItsIntrinsicSize() {
		assertEquals(
			Size(Dimension.Undefined, Dimension(450)),
			resolveCoverImageSize(null, Dimension(450), 0, 0),
		)
	}

	@Test
	fun anIncompleteAspectRatioCannotDivideByZero() {
		assertEquals(
			Size(Dimension(300), Dimension.Undefined),
			resolveCoverImageSize(Dimension(300), null, 0, 3),
		)
		assertEquals(
			Size(Dimension.Undefined, Dimension(450)),
			resolveCoverImageSize(null, Dimension(450), 2, 0),
		)
	}

	@Test
	fun unknownViewBoundsStillWaitForLayout() {
		assertNull(resolveCoverImageSize(null, null, 0, 0))
		assertNull(resolveCoverImageSize(null, null, 2, 3))
	}

	@Test
	fun knownRatiosAndExplicitBoundsKeepTheirExistingSizes() {
		assertEquals(Size(300, 450), resolveCoverImageSize(Dimension(300), null, 2, 3))
		assertEquals(Size(300, 450), resolveCoverImageSize(null, Dimension(450), 2, 3))
		assertEquals(Size(300, 200), resolveCoverImageSize(Dimension(300), Dimension(200), 2, 3))
		assertEquals(Size(300, 200), resolveCoverImageSize(Dimension(300), Dimension(200), 0, 0))
	}
}
