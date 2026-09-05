package org.koitharu.kotatsu.backups.data

import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.AppSettings

class SensitiveBackupKeysTest {

	@Test
	fun excludesCredentialsAndPrivateSessionState() {
		assertTrue(AppSettings.KEY_APP_PASSWORD in SensitiveBackupKeys.values)
		assertTrue(AppSettings.KEY_PROXY_PASSWORD in SensitiveBackupKeys.values)
		assertTrue(AppSettings.KEY_DISCORD_TOKEN in SensitiveBackupKeys.values)
		assertTrue(AppSettings.KEY_TRANSLATE_API_KEY in SensitiveBackupKeys.values)
		assertTrue(AppSettings.KEY_TRANSLATE_CUSTOM_HEADERS in SensitiveBackupKeys.values)
		assertTrue(AppSettings.KEY_INCOGNITO_MODE in SensitiveBackupKeys.values)
	}
}
