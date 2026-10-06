package org.koitharu.kotatsu.scrobbling.common.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.trackerStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerLibraryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val db: MangaDatabase,
) {
    private val cache = TrackerLibraryCache(context.getSharedPreferences("tracker_libraries", Context.MODE_PRIVATE))
    private val locks = ScrobblerService.entries.associateWith { Mutex() }

    fun scrobbler(service: ScrobblerService): Scrobbler = scrobblers.first { it.scrobblerService == service }

    suspend fun session(service: ScrobblerService): TrackerSession {
        val repository = scrobbler(service).trackerRepository
        return repository.accountSession()
    }

    fun invalidate(service: ScrobblerService, account: Long) {
        cache.invalidate(service, account)
    }

    suspend fun cached(service: ScrobblerService, session: TrackerSession): List<TrackerLibraryEntry>? {
        val storage = scrobbler(service).trackerRepository.storage
        storage.checkSession(session)
        val owner = requireNotNull(session.accountId)
        val parsed = cache.read(service, owner) ?: return null
        val linked = parsed.map { entry ->
            val local = db.getScrobblingDao().findAllByTarget(service.id, entry.mediaId, owner).firstOrNull()
            if (local == null) entry.copy(localMangaId = null)
            else entry.copy(localMangaId = local.mangaId, listEntryId = local.id,
                status = trackerStatus(service, local.status.orEmpty()), progress = local.chapter,
                notes = local.comment, score = local.rating * 10)
        }
        storage.checkSession(session)
        return linked
    }

    suspend fun refresh(service: ScrobblerService, session: TrackerSession, force: Boolean = false): List<TrackerLibraryEntry> =
        locks.getValue(service).withLock outer@ {
            scrobbler(service).trackerRepository.storage.mutationMutex.withLock {
                val scrobbler = scrobbler(service)
                val storage = scrobbler.trackerRepository.storage
                storage.checkSession(session)
                val owner = requireNotNull(session.accountId)
                val cached = cached(service, session)
                if (!force && cached != null && cache.isFresh(service, owner, System.currentTimeMillis())) return@outer cached
                val entries = storage.withSession(session) { scrobbler.trackerRepository.fetchLibrary() }
                val replacedRates = mutableListOf<Long>()
                val linked = db.withTransaction {
                    entries.map { entry ->
                        storage.checkSession(session)
                        val matches = db.getScrobblingDao().findAllByTarget(service.id, entry.mediaId, owner)
                        matches.forEach { previous ->
                            db.getScrobblingDao().refreshLinked(ScrobblingEntity(service.id, entry.listEntryId, previous.mangaId,
                                entry.mediaId, scrobbler.remoteStatus(entry.status), entry.progress, entry.notes,
                                entry.score / 10, owner), previous.id)
                            if (previous.id != entry.listEntryId) replacedRates += previous.mangaId
                        }
                        entry.copy(localMangaId = matches.firstOrNull()?.mangaId)
                    }.also { storage.checkSession(session) }
                }
                storage.checkSession(session)
                replacedRates.forEach { storage.progress.invalidate(owner, it) }
                cache.write(service, owner, linked, System.currentTimeMillis())
                linked
            }
        }
}
