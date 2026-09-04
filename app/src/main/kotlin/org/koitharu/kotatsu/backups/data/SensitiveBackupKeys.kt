package org.koitharu.kotatsu.backups.data

import org.koitharu.kotatsu.core.prefs.AppSettings

object SensitiveBackupKeys {
	val values: Set<String> = setOf(
		AppSettings.KEY_APP_PASSWORD,
		AppSettings.KEY_APP_PASSWORD_NUMERIC,
		AppSettings.KEY_PROXY_LOGIN,
		AppSettings.KEY_PROXY_PASSWORD,
		AppSettings.KEY_INCOGNITO_MODE,
		AppSettings.KEY_BACKUP_TG_TOKEN,
		AppSettings.KEY_DISCORD_TOKEN,
		AppSettings.KEY_DISCORD_REFRESH_TOKEN,
		AppSettings.KEY_DISCORD_LAST_CODE,
		AppSettings.KEY_DISCORD_CODE_VERIFIER,
		AppSettings.KEY_TRANSLATE_API_KEY,
		AppSettings.KEY_TRANSLATE_CUSTOM_HEADERS,
	)
}
