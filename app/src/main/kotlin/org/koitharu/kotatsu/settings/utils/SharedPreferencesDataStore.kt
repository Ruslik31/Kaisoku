package org.koitharu.kotatsu.settings.utils

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore

/** Binds preferences to a [SharedPreferences] file other than the screen's own. */
class SharedPreferencesDataStore(
	private val prefs: SharedPreferences,
) : PreferenceDataStore() {

	override fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }

	override fun putStringSet(key: String, values: MutableSet<String>?) = prefs.edit { putStringSet(key, values) }

	override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }

	override fun putLong(key: String, value: Long) = prefs.edit { putLong(key, value) }

	override fun putFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }

	override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

	override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)

	override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
		prefs.getStringSet(key, defValues)

	override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)

	override fun getLong(key: String, defValue: Long): Long = prefs.getLong(key, defValue)

	override fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)

	override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
}
