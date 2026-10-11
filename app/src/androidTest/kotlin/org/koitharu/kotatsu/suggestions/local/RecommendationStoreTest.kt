package org.koitharu.kotatsu.suggestions.local

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RecommendationStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun offlineCacheRetainsErrorsAndRejectsChangedSourceVersions() = fixture { library, store ->
        val value = Recommendation(RecommendationTarget.SourceTitle(title.toManga(emptySet(), null)))
        store.dao.saveCandidates(listOf(RecommendationCandidateEntity(value.key,
            RecommendationCodec.encode(value).toString(), 10, "source-v1")))
        val row = RecommendationResultEntity("explore", JSONArray(listOf(value.key)).toString(), 10, "scope", "offline")
        store.dao.result(row)
        val observed = store.dao.observe("explore").first()!!
        assertEquals("offline", observed.error)
        assertEquals(value.key, store.results(observed, mapOf("fixture" to "source-v1")).single().key)
        assertTrue(store.results(observed, mapOf("fixture" to "source-v2")).isEmpty())
        assertEquals(1, library.getMangaDao().recommendationTaste().first().size)
    }

    @Test fun feedbackUndoAndCacheTrimmingStayInThePrivateStore() = fixture { library, store ->
        val before = snapshot(library)
        val key = "source:fixture:-88"
        store.dao.dismiss(RecommendationDismissalEntity(key, 1))
        assertEquals(listOf(key), store.dao.dismissed())
        store.dao.undo(key)
        assertTrue(store.dao.dismissed().isEmpty())
        store.dao.saveCandidates((1..2001).map { RecommendationCandidateEntity("candidate:$it", "{}", it.toLong(), "v1") })
        store.dao.trim()
        assertEquals(2000, store.dao.candidates().size)
        assertTrue(store.dao.candidates(listOf("candidate:1")).isEmpty())
        assertEquals(before, snapshot(library))
    }

    @Test fun favoritesCountOnceAndMissingStatisticsFallBackWithoutChangingSettings() = fixture { library, _ ->
        library.getFavouriteCategoriesDao().upsert(FavouriteCategoryEntity(2, 2, 2, "Other category", "NEWEST", true, true, 0))
        library.getFavouritesDao().upsert(FavouriteEntity(-88, 2, 1, false, 1, 0))
        var taste = library.getMangaDao().recommendationTaste().first().single()
        assertTrue(taste.favorite)
        assertEquals(0L, taste.durationMillis)
        assertEquals(50, taste.historyChapters)
        library.getStatsDao().upsert(StatsEntity(-88, 1, 500, 1))
        library.getStatsDao().upsert(StatsEntity(-88, 2, 600, 1))
        taste = library.getMangaDao().recommendationTaste().first().single()
        assertEquals(1100L, taste.durationMillis)
    }

    @Test fun disabledPublicServicesMakeNoRequestsAndConfirmationNeverWritesLibraryOrTrackers() = fixture { library, store ->
        val prefix = "recommendation-test-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val settings = AppSettings(isolated)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("No network is needed by this fixture") }.build()
        val providers = PublicRecommendationProviders(client, settings, store, library)
        val seed = title.toManga(emptySet(), null)
        val before = snapshot(library)
        assertTrue(providers.links(seed).isEmpty())
        assertTrue(providers.related(RecommendationProviderLink("source:fixture:-88", "ANILIST", "42", "MANGA")) { _, _ ->
            error("Disabled public recommendations must not schedule requests")
        }.isEmpty())
        assertTrue(runCatching { providers.search("ANILIST", seed) }.isFailure)
        assertEquals(0, calls.get())
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(isolated).edit()
            .putBoolean(AppSettings.KEY_SUGGESTIONS_PUBLIC, true).commit()
        val result = PublicRecommendationMetadata.anilistTitle(JSONObject(
            """{"id":42,"format":"MANGA","title":{"english":"Fixture"}}"""))!!
        providers.confirm(seed, result)
        assertEquals("42", store.dao.links("source:fixture:-88").single().identifier)
        assertEquals(before, snapshot(library))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(isolated).edit()
            .putBoolean(AppSettings.KEY_SUGGESTIONS_LEGACY, true).commit()
        assertTrue(runCatching { providers.search("ANILIST", seed) }.isFailure)
        assertEquals(0, calls.get())
    }

    @Test fun cachedIdentitiesFollowCorrectedTrackerMatchesWithoutPublicRequests() = fixture { library, store ->
        val prefix = "recommendation-identity-test-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val settings = AppSettings(isolated)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("No public request expected") }.build()
        val providers = PublicRecommendationProviders(client, settings, store, library)
        library.getScrobblingDao().upsert(ScrobblingEntity(2, 0, -88, 42, null, 0, null, 0f, 7))
        val card = Recommendation(RecommendationTarget.SourceTitle(title.toManga(emptySet(), null)))
        val first = providers.withTrustedIdentities(listOf(card)).single()
        assertEquals("42", first.trustedIdentifiers["ANILIST"])
        library.getScrobblingDao().upsert(ScrobblingEntity(2, 0, -88, 43, null, 0, null, 0f, 7))
        val corrected = providers.withTrustedIdentities(listOf(first)).single()
        assertEquals("43", corrected.trustedIdentifiers["ANILIST"])
        assertEquals(0, calls.get())
    }

    private fun fixture(block: suspend (MangaDatabase, RecommendationStore) -> Unit) = runBlocking {
        val library = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val database = Room.inMemoryDatabaseBuilder(context, RecommendationDatabase::class.java).build()
        try {
            library.getMangaDao().upsert(title)
            library.getHistoryDao().upsert(HistoryEntity(-88, 1, 2, 777, 3, 5555f, .34f, 0, 50))
            library.getFavouriteCategoriesDao().upsert(FavouriteCategoryEntity(1, 1, 1, "Category", "NEWEST", true, true, 0))
            library.getFavouritesDao().upsert(FavouriteEntity(-88, 1, 1, false, 1, 0))
            block(library, RecommendationStore(database, library))
        } finally { database.close(); library.close() }
    }

    private fun snapshot(database: MangaDatabase) = listOf("history", "favourites", "favourite_categories", "manga", "scrobblings")
        .map { table -> database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) }) }
        } }

    private val title = MangaEntity(-88, "Fixture", null, "/title", "https://fixture.invalid/title", .5f,
        false, "SAFE", "", null, null, null, "fixture")
}
