package org.koitharu.kotatsu.suggestions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.suggestions.data.SuggestionEntity
import org.koitharu.kotatsu.suggestions.domain.RecommendationExclusions
import org.koitharu.kotatsu.suggestions.domain.toLegacyManga
import org.koitharu.kotatsu.suggestions.domain.withRecommendationExclusions

@RunWith(AndroidJUnit4::class)
class LegacyRecommendationTagsTest {
    @Test fun cachedTagsReachTheLegacyFilterBeforeTheDisplayLimit() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MangaDatabase::class.java).build()
        try {
            for ((index, genre) in listOf("Drama", "Adventure").withIndex()) {
                val id = index + 1L
                val tag = TagEntity(id, genre, genre, "TEST", false)
                db.getTagsDao().upsert(listOf(tag))
                db.getMangaDao().upsert(MangaBackup(id, "Title $id", url = "/$id",
                    publicUrl = "https://fixture.invalid/$id", coverUrl = "", source = "TEST").toEntity(), listOf(tag))
                db.getSuggestionDao().upsert(SuggestionEntity(id, (10 - index) / 10f, 1))
            }
            val cached = db.getSuggestionDao().observeAll(0, emptySet()).first().map { it.toLegacyManga() }
            assertEquals(setOf("Drama"), cached.first().tags.map { it.title }.toSet())
            val visible = flowOf(cached).withRecommendationExclusions(
                flowOf(RecommendationExclusions(setOf("Drama"), false)), 1,
            ).first()
            assertEquals(listOf(2L), visible.map { it.id })
            assertEquals(2, db.getSuggestionDao().count())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM history").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }
}
