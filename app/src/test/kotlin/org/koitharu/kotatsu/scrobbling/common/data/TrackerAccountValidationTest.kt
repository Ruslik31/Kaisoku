package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class TrackerAccountValidationTest {
    @Test fun aRestoredProfileCannotBindATokenToTheWrongAccount() = runTest {
        val repo = Repository()
        repo.storage.accessToken = "second"
        repo.storage.user = ScrobblerUser(1, "First", null, ScrobblerService.ANILIST)
        assertEquals(2L, repo.accountSession().accountId)
        assertEquals(2L, repo.storage.user?.id)
    }

    @Test fun concurrentIdentityChecksAreCombinedAndAValidatedSessionIsReused() = runTest {
        val repo = Repository(); repo.storage.accessToken = "first"
        val sessions = List(3) { async { repo.accountSession() } }.map { it.await() }
        assertEquals(listOf(1L, 1L, 1L), sessions.map { it.accountId })
        repo.accountSession(); assertEquals(1, repo.loads)
        repo.storage.accessToken = "second"
        assertEquals(2L, repo.accountSession().accountId); assertEquals(2, repo.loads)
    }

    private class Repository : ScrobblerRepository {
        override val storage = ScrobblerStorage(MemoryPreferences(), MemoryPreferences(), MemoryPreferences(), ScrobblerService.ANILIST)
        var loads = 0
        override val cachedUser get() = storage.user
        override val oauthUrl = ""
        override val isAuthorized get() = storage.accessToken != null
        override suspend fun loadUser(): ScrobblerUser {
            val session = storage.requestSession(); loads++; delay(100)
            return ScrobblerUser(if (session.token == "first") 1 else 2, "Fixture", null, ScrobblerService.ANILIST)
                .also { storage.saveUser(session, it) }
        }
        override suspend fun fetchLibrary(): List<TrackerLibraryEntry> = emptyList()
        override suspend fun authorize(code: String?) = Unit
        override fun logout() = storage.clear()
        override suspend fun unregister(mangaId: Long) = Unit
        override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> = emptyList()
        override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo = error("Unused")
        override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long, allowCreate: Boolean) = true
        override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) = Unit
        override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?, setStartDate: Boolean) = Unit
    }
}
