package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A credential snapshot. Never include the credential in logs or user-visible errors. */
class TrackerSession(val accountId: Long?, val revision: Long, internal val token: String) {
    override fun toString(): String = "TrackerSession(accountId=$accountId, revision=$revision)"
}

class TrackerSessionChangedException : CancellationException("Tracker account changed")

internal class TrackerRequestSession(val storage: ScrobblerStorage, val session: TrackerSession) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TrackerRequestSession>
}

suspend fun ScrobblerStorage.requestSession(): TrackerSession {
    val inherited = currentCoroutineContext()[TrackerRequestSession]
    val session = if (inherited?.storage === this) inherited.session else snapshot()
    checkSession(session)
    return session
}

suspend fun <T> ScrobblerStorage.withSession(block: suspend (TrackerSession) -> T): T {
    val session = requestSession()
    return withContext(TrackerRequestSession(this, session)) {
        block(session).also { checkSession(session) }
    }
}

internal class TrackerLinkRequest(val mangaId: Long, val targetId: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TrackerLinkRequest>
}

suspend fun ScrobblingDao.saveLinked(entity: ScrobblingEntity) {
    val link = currentCoroutineContext()[TrackerLinkRequest]
    if (link?.mangaId == entity.mangaId) {
        check(link.targetId == entity.targetId) { "Tracker returned a different title" }
        replace(entity)
    }
    else updateLinked(entity)
}

suspend fun <T> ScrobblerStorage.withSession(session: TrackerSession, block: suspend () -> T): T {
    checkSession(session)
    return withContext(TrackerRequestSession(this, session)) { block().also { checkSession(session) } }
}

class TrackerEntryCreationRequired : IllegalStateException("Confirm adding this title to the remote list")

class TrackerRemoteEntryMissing : IllegalStateException("This title is no longer on the remote list. Relink it to continue syncing.")

internal fun nextTrackerProgress(requested: Int, remote: Int): Int? = requested.takeIf { it > remote && it >= 0 }

/** Validate restored credentials once per session, combining concurrent profile checks. */
suspend fun ScrobblerRepository.accountSession(): TrackerSession = storage.identityMutex.withLock {
    val before = storage.requestSession()
    if (storage.verifiedRevision != before.revision || before.accountId == null) {
        loadUser()
        storage.verifiedRevision = storage.sessionVersion
    }
    storage.snapshot()
}
