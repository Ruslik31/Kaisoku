package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser

class TrackerSessionTest {
    private fun storage() = ScrobblerStorage(MemoryPreferences(), MemoryPreferences(), MemoryPreferences(), ScrobblerService.ANILIST)
    private fun ScrobblerStorage.connect(id: Long, token: String) { accessToken = token; user = ScrobblerUser(id, "user$id", null, ScrobblerService.ANILIST) }

    @Test fun switchingAccountsRejectsOldSnapshotsEvenWhenTheSameAccountReturns() {
        val store = storage(); store.connect(1, "first-token")
        val first = store.snapshot()
        store.connect(2, "other-token"); store.connect(1, "first-token")
        assertTrue(runCatching { store.checkSession(first) }.exceptionOrNull() is TrackerSessionChangedException)
        assertFalse(first.toString().contains("first-token"))
    }

    @Test fun logoutAndDelayedProfileOrTokenResponsesCannotRestoreCredentials() {
        val store = storage(); store.connect(1, "first")
        val first = store.snapshot(); val version = store.sessionVersion
        store.clear()
        assertTrue(runCatching { store.saveUser(first, ScrobblerUser(1, "late", null, ScrobblerService.ANILIST)) }.isFailure)
        assertTrue(runCatching { store.saveTokens(version, "late", null) }.isFailure)
        assertNull(store.accessToken); assertNull(store.user)
    }

    @Test fun inheritedRequestKeepsItsOriginalOwnership() = runTest {
        val store = storage(); store.connect(1, "first")
        val session = store.snapshot()
        val thrown = runCatching { store.withSession(session) {
            store.connect(2, "other")
            store.requestSession()
        } }.exceptionOrNull()
        assertTrue(thrown is TrackerSessionChangedException)
    }

    @Test fun requestsForDifferentServicesUseTheirOwnCredentials() = runTest {
        val first = storage(); first.connect(1, "one")
        val second = storage(); second.connect(2, "two")
        first.withSession { assertEquals(2L, second.requestSession().accountId) }
    }

    @Test fun reauthorizationClearsPreviousOwnerBeforePublishingNewCredentials() {
        val store = storage(); store.connect(1, "old")
        store.beginAuthorization(); val version = store.sessionVersion
        assertNull(store.user); assertNull(store.accessToken)
        store.saveTokens(version, "new", "refresh")
        assertNull(store.user); assertEquals("new", store.accessToken)
    }

    @Test fun negativeMangaBakaAccountHashesRemainValidOwners() {
        val store = ScrobblerStorage(MemoryPreferences(), MemoryPreferences(), MemoryPreferences(), ScrobblerService.MANGABAKA)
        store.accessToken = "fixture"
        store.user = ScrobblerUser(-1_234, "Fixture", null, ScrobblerService.MANGABAKA)
        assertEquals(-1_234L, store.snapshot().accountId)
        store.checkSession(store.snapshot())
    }

    @Test fun malformedRestoredProfilesDoNotCrashCredentialValidation() {
        val prefs = MemoryPreferences()
        val store = ScrobblerStorage(prefs, MemoryPreferences(), MemoryPreferences(), ScrobblerService.ANILIST)
        prefs.edit().putString("access_token", "fixture").putString("user", "bad-id\nName\n\nANILIST").apply()
        assertNull(store.snapshot().accountId)
        prefs.edit().putString("user", "1\nName\n\nUNKNOWN_SERVICE").apply()
        assertNull(store.snapshot().accountId)
        prefs.edit().putString("user", "1\nName\n\nMAL").apply()
        assertNull(store.snapshot().accountId)
    }
}
