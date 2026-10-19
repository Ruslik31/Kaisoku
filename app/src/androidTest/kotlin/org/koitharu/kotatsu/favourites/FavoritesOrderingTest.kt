package org.koitharu.kotatsu.favourites

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavoriteOrderEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.list.domain.ListSortOrder

/** In-memory only: do not open the installed application's library. */
@RunWith(AndroidJUnit4::class)
class FavoritesOrderingTest {
    @Test fun globalAndPerCategoryRanksApplyBeforePaginationWithoutTouchingMemberships() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MangaDatabase::class.java).build()
        try {
            seed(db)
            val membership = db.getFavouritesDao().findAllRaw(1)
            db.getFavoriteOrderDao().upsert(FavoriteOrderEntity(3, 0, 0, 10, "global"))
            db.getFavoriteOrderDao().upsert(FavoriteOrderEntity(2, 1, 0, 11, "category"))
            assertEquals(listOf(3L), db.getFavouritesDao().observeAll(ListSortOrder.MANUAL, emptySet(), 1).first().map { it.manga.id })
            assertEquals(listOf(2L), db.getFavouritesDao().observeAll(1, ListSortOrder.MANUAL, emptySet(), 1).first().map { it.manga.id })
            assertEquals(listOf(1L, 2L, 3L), db.getFavouritesDao().observeAll(2, ListSortOrder.MANUAL, emptySet(), 10).first().map { it.manga.id })
            assertEquals(membership, db.getFavouritesDao().findAllRaw(1))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM history").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun categoryLinksUseExactIdentityAndHideDeletedOrPrivateCategories() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MangaDatabase::class.java).build()
        try {
            seed(db)
            db.getFavouriteCategoriesDao().updateVisibility(2, false)
            db.getFavouritesDao().delete(1, 1)
            val links = db.getFavouritesDao().searchCategories(listOf(1, 2, 99))
            assertEquals(listOf(2L), links.map { it.mangaId })
            assertEquals(1, links.single().category.categoryId)
            db.getFavouriteCategoriesDao().delete(1)
            assertTrue(db.getFavouritesDao().searchCategories(listOf(2)).isEmpty())
        } finally { db.close() }
    }

    @Test fun tilesCountDistinctTitlesAndBoundPreviewWithoutRemoteMetadata() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MangaDatabase::class.java).build()
        try {
            seed(db)
            assertEquals(3, db.getFavouritesDao().visibleFavoritesCount(false))
            assertEquals(listOf(3, 3), db.getFavouritesDao().categoryCounts(false).sortedBy { it.category_id }.map { it.count })
            assertEquals(2, db.getFavouritesDao().categoryPreviewCovers(0, false, 2).size)
            assertEquals(3, db.getFavouritesDao().categoryPreviewCovers(1, false, 4).size)
            db.getFavouriteCategoriesDao().updateVisibility(1, false)
            db.getFavouriteCategoriesDao().updateVisibility(2, false)
            assertEquals(0, db.getFavouritesDao().visibleFavoritesCount(false))
            assertTrue(db.getFavouritesDao().categoryPreviewCovers(0, false, 4).isEmpty())
        } finally { db.close() }
    }

    private suspend fun seed(db: MangaDatabase) {
        for (category in 1..2) db.getFavouriteCategoriesDao().upsert(
            FavouriteCategoryEntity(categoryId = category, title = "Category $category", sortKey = category,
                order = "NEWEST", track = true, isVisibleInLibrary = true, createdAt = category.toLong(), deletedAt = 0),
        )
        for (id in 1L..3L) {
            db.getMangaDao().upsert(MangaBackup(id, "Same title", url = "/$id", publicUrl = "https://example.org/$id",
                coverUrl = "https://example.org/$id.jpg", source = "MANGADEX").toEntity(), emptyList())
            for (category in 1L..2L) db.getFavouritesDao().upsert(FavouriteEntity(id, category, 50, true, 100 - id, 0))
        }
    }
}
