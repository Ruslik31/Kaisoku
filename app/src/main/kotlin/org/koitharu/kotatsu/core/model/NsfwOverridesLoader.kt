package org.koitharu.kotatsu.core.model

import androidx.room.InvalidationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_SOURCES
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Keeps [NsfwSourceOverrides] in sync with the `sources` table.
 *
 * Registered as a database observer in `AppModule.provideDatabaseObservers`, so the first load
 * happens during app startup. [ensureLoaded] closes the window where a source could briefly be
 * shown with its intrinsic rating before that first load completes.
 */
@Singleton
class NsfwOverridesLoader @Inject constructor(
	private val database: Provider<MangaDatabase>,
) : InvalidationTracker.Observer(TABLE_SOURCES) {

	private val mutex = Mutex()

	@Volatile
	private var isLoaded = false

	override fun onInvalidated(tables: Set<String>) {
		isLoaded = false
		processLifecycleScope.launch(Dispatchers.Default) {
			reload()
		}
	}

	/**
	 * Loads the overrides unless a load already succeeded and nothing invalidated them since.
	 */
	suspend fun ensureLoaded() {
		if (isLoaded) {
			return
		}
		reload()
	}

	/**
	 * Unconditionally re-reads the overrides from the database.
	 */
	suspend fun reload() = mutex.withLock {
		val overrides = database.get().getSourcesDao().findAllNsfwOverrides()
		NsfwSourceOverrides.replaceAll(
			overrides.associate { it.source to (it.nsfwOverride != 0) },
		)
		isLoaded = true
	}
}
