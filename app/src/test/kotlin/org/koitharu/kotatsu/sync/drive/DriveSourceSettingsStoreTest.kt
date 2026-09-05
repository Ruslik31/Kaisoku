package org.koitharu.kotatsu.sync.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSourceSettingsStoreTest {

	@Test
	fun sourceSettingsUseAStrictNonCredentialAllowlist() {
		val keys = DriveSourceSettingsStore.ALLOWED_KEYS
		assertTrue("domain" in keys)
		assertTrue("img_server" in keys)
		assertFalse("authorization" in keys)
		assertFalse("password" in keys)
		assertFalse("token" in keys)
		assertFalse("cookie" in keys)
	}
}
