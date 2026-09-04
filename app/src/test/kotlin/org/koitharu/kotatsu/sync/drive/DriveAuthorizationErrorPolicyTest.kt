package org.koitharu.kotatsu.sync.drive

import com.google.android.gms.common.api.CommonStatusCodes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorizationErrorPolicyTest {

	@Test
	fun recognizesApiConsoleRegistrationFailures() {
		assertTrue(
			DriveAuthorizationErrorPolicy.isApiConsoleSetupError(
				CommonStatusCodes.INTERNAL_ERROR,
				"8: [8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]",
			),
		)
		assertTrue(
			DriveAuthorizationErrorPolicy.isApiConsoleSetupError(
				CommonStatusCodes.DEVELOPER_ERROR,
				null,
			),
		)
	}

	@Test
	fun doesNotMisclassifyDeviceOrNetworkFailures() {
		assertFalse(
			DriveAuthorizationErrorPolicy.isApiConsoleSetupError(
				CommonStatusCodes.API_NOT_CONNECTED,
				"API is not available on this device",
			),
		)
		assertFalse(
			DriveAuthorizationErrorPolicy.isApiConsoleSetupError(
				CommonStatusCodes.NETWORK_ERROR,
				"Network error",
			),
		)
	}
}
