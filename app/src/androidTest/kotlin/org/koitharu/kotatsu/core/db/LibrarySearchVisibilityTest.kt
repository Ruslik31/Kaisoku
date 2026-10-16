package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity

@RunWith(AndroidJUnit4::class)
class LibrarySearchVisibilityTest {

	@Test
	fun searchIgnoresOnlyDeletedLibraryMembershipsAndKeepsLiveAlternatives() = runTest {
		val db = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext(), MangaDatabase::class.java,
		).build()
		try {
			db.getFavouriteCategoriesDao().upsert(
				FavouriteCategoryEntity(1, 0L, 0, "Library", "ALPHABETIC", false, true, 0L),
			)
			for (id in 1L..7L) {
				db.openHelper.writableDatabase.execSQL(
					"""INSERT INTO manga (manga_id, title, alt_title, url, public_url, rating, nsfw,
						content_rating, cover_url, large_cover_url, state, author, source)
						VALUES (?, ?, ?, ?, ?, -1, 0, NULL, '', NULL, NULL, NULL, ?)""",
					arrayOf<Any>(id, "Title $id", "Needle $id", "/$id", "https://example.org/$id",
						if (id == 7L) "OTHER" else "REMANGA"),
				)
			}
			for (id in listOf(1L, 2L, 3L, 4L, 7L)) {
				db.getFavouritesDao().upsert(
					FavouriteEntity(id, 1L, 0, false, 0L, if (id in listOf(1L, 3L)) 10L else 0L),
				)
			}
			for (id in listOf(1L, 3L, 4L, 5L)) {
				db.getHistoryDao().insert(
					HistoryEntity(id, 0L, 0L, 100L, 2, 0.3f, 0.5f, if (id == 3L) 0L else 10L, 10),
				)
			}
			assertEquals(setOf(2L, 3L, 4L, 7L),
				db.getMangaDao().searchByTitle("%Needle%", 20).map { it.manga.id }.toSet())
			assertEquals(setOf(2L, 3L, 4L),
				db.getMangaDao().searchByTitle("%Needle%", "REMANGA", 20).map { it.manga.id }.toSet())
			// Search is read-only, including the deletion records kept for synchronization.
			assertEquals(10L, db.getHistoryDao().findForRestore(1L)?.deletedAt)
			assertEquals(2, db.getHistoryDao().findForRestore(3L)?.page)
		} finally {
			db.close()
		}
	}
}
