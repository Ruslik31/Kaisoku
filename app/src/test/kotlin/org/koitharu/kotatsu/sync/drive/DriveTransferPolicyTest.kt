package org.koitharu.kotatsu.sync.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTransferPolicyTest {

	@Test
	fun parsesServerConfirmedOffsets() {
		assertEquals(1_048_576L, DriveTransferPolicy.acknowledgedOffset("bytes=0-1048575"))
		assertEquals(0L, DriveTransferPolicy.acknowledgedOffset(null))
		assertEquals(1_048_576L, DriveTransferPolicy.contentRangeStart("bytes 1048576-2097151/3000000"))
		assertNull(DriveTransferPolicy.contentRangeStart("invalid"))
	}

	@Test
	fun retriesOnlyTransientHttpFailures() {
		assertTrue(DriveTransferPolicy.isRetryableHttp(408))
		assertTrue(DriveTransferPolicy.isRetryableHttp(429))
		assertTrue(DriveTransferPolicy.isRetryableHttp(503))
		assertFalse(DriveTransferPolicy.isRetryableHttp(401))
		assertFalse(DriveTransferPolicy.isRetryableHttp(404))
	}
}
