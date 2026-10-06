package org.koitharu.kotatsu.scrobbling.common.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.common.domain.canonicalTrackerId
import org.koitharu.kotatsu.scrobbling.common.domain.uniqueTitleSuggestion
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject
import javax.inject.Singleton

/** Matching only runs from an active reader, after an explicit opt-in. It never creates remote entries. */
@Singleton
class TrackerMatchingCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: AppSettings,
    private val library: TrackerLibraryRepository,
    private val db: MangaDatabase,
    private val dataRepository: org.koitharu.kotatsu.core.parser.MangaDataRepository,
    private val aniList: AniListRepository,
) {
    data class Suggestion(val manga: Manga, val targetId: Long?, val session: TrackerSession)
    private val prefs = context.getSharedPreferences("tracker_matching", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableSuggestions = MutableStateFlow<Suggestion?>(null)
    val suggestions = mutableSuggestions.asStateFlow()

    fun isCurrent(suggestion: Suggestion): Boolean = settings.isTrackerAutoMatchEnabled && runCatching {
        aniList.storage.checkSession(suggestion.session)
    }.isSuccess

    fun claimNotice(suggestion: Suggestion): Boolean {
        if (!isCurrent(suggestion)) return false
        val key = "shown_${suggestion.session.accountId}_${suggestion.manga.id}_${suggestion.targetId}"
        if (fresh(prefs.getLong(key, 0))) return false
        prefs.edit { putLong(key, System.currentTimeMillis()) }
        return true
    }

    suspend fun match(manga: Manga, incognito: Boolean) {
        if (incognito || !settings.isTrackerAutoMatchEnabled || !aniList.isAuthorized) return
        mutex.withLock {
            if (incognito || !settings.isTrackerAutoMatchEnabled || !aniList.isAuthorized) return
            try {
                val session = library.session(ScrobblerService.ANILIST)
                val owner = requireNotNull(session.accountId)
                aniList.storage.withSession(session) {
                    if (db.getScrobblingDao().find(ScrobblerService.ANILIST.id, manga.id, owner) != null ||
                        aniList.storage.progress.wasManuallyMatched(owner, manga.id)) return@withSession
                    val key = "attempt_${owner}_${manga.id}"
                    if (fresh(prefs.getLong(key, 0))) return@withSession
                    val trusted = mutableSetOf<Long>()
                    canonicalTrackerId(manga.publicUrl, "anilist.co")?.let(trusted::add)
                    val mal = library.scrobbler(ScrobblerService.MAL)
                    val malOwner = mal.trackerRepository.cachedUser?.id
                    val malId = canonicalTrackerId(manga.publicUrl, "myanimelist.net") ?: if (mal.isEnabled && malOwner != null) {
                        db.getScrobblingDao().find(ScrobblerService.MAL.id, manga.id, malOwner)?.targetId
                    } else null
                    if (malId != null) aniList.resolveMalId(malId)?.let(trusted::add)
                    if (!settings.isTrackerAutoMatchEnabled) return@withSession
                    val target = trusted.singleOrNull()
                    if (target != null) {
                        val remote = library.refresh(ScrobblerService.ANILIST, session)
                        if (remote.any { it.mediaId == target }) {
                            try {
                                dataRepository.storeManga(manga, replaceExisting = false)
                                library.scrobbler(ScrobblerService.ANILIST).linkManga(manga.id, target)
                                library.invalidate(ScrobblerService.ANILIST, owner)
                                mutableSuggestions.value = null
                            } catch (_: TrackerEntryCreationRequired) {
                                mutableSuggestions.value = Suggestion(manga, target, session)
                            }
                        } else mutableSuggestions.value = Suggestion(manga, target, session)
                    } else {
                        val names = (setOf(manga.title) + manga.altTitles).filter(String::isNotBlank).take(3)
                        val matches = names.flatMap { aniList.findManga(it, 0) }.distinctBy { it.id }
                        if (matches.isNotEmpty()) mutableSuggestions.value = Suggestion(manga,
                            uniqueTitleSuggestion(names.toSet(), matches), session)
                    }
                    aniList.storage.checkSession(session)
                    prefs.edit { putLong(key, System.currentTimeMillis()) }
                }
            } catch (e: TrackerSessionChangedException) {
                mutableSuggestions.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Optional discovery never interrupts reading. Manual Track and the library retain their errors.
            }
        }
    }

    private fun fresh(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        return timestamp > 0 && now >= timestamp && now - timestamp < 86_400_000
    }
}
