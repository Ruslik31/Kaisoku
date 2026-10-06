package org.koitharu.kotatsu.scrobbling.common.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jsoup.internal.StringUtil.StringJoiner
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser

private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_USER = "user"
private const val KEY_REVISION = "session_revision"

class ScrobblerStorage internal constructor(
    private val prefs: android.content.SharedPreferences,
    progressPrefs: android.content.SharedPreferences,
    private val libraryPrefs: android.content.SharedPreferences,
    service: ScrobblerService,
) {
    constructor(context: Context, service: ScrobblerService) : this(
        context.getSharedPreferences(service.name, Context.MODE_PRIVATE),
        context.getSharedPreferences("tracker_progress_${service.name}", Context.MODE_PRIVATE),
        context.getSharedPreferences("tracker_libraries", Context.MODE_PRIVATE),
        service,
    )

    val mutationMutex = kotlinx.coroutines.sync.Mutex()
    internal val identityMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile internal var verifiedRevision = -1L
    val progress = TrackerProgressStore(progressPrefs)

    val requestGate = TrackerRequestGate(if (service == ScrobblerService.ANILIST) 2_100L else 350L)

    private val libraryPrefix = service.name
    fun invalidateLibrary(account: Long) { libraryPrefs.edit { remove("${libraryPrefix}_${account}_updated") } }

    private val changes = MutableStateFlow(prefs.getLong(KEY_REVISION, 0L))
    val sessionChanges = changes.asStateFlow()

    @Synchronized
    fun snapshot(): TrackerSession = TrackerSession(user?.id, prefs.getLong(KEY_REVISION, 0L), accessToken
        ?: throw IllegalStateException("Tracker is not connected"))

    @Synchronized
    fun checkSession(session: TrackerSession) {
        if (session.revision != prefs.getLong(KEY_REVISION, 0L) || session.token != accessToken ||
            session.accountId != user?.id) throw TrackerSessionChangedException()
    }

    @Synchronized
    fun saveUser(session: TrackerSession, value: ScrobblerUser) {
        checkSession(session)
        user = value
    }

    @Synchronized
    fun beginAuthorization() {
        prefs.edit { remove(KEY_USER); remove(KEY_ACCESS_TOKEN); remove(KEY_REFRESH_TOKEN); bumpSession(this) }
        publishSession()
    }

    private fun bumpSession(editor: android.content.SharedPreferences.Editor) {
        val next = prefs.getLong(KEY_REVISION, 0L) + 1
        editor.putLong(KEY_REVISION, next)
    }

    val sessionVersion: Long get() = prefs.getLong(KEY_REVISION, 0L)

    private fun publishSession() { changes.value = sessionVersion }

    @Synchronized
    fun saveTokens(version: Long, token: String, refresh: String?) {
        if (version != sessionVersion) throw TrackerSessionChangedException()
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, token)
            putString(KEY_REFRESH_TOKEN, refresh ?: refreshToken)
            bumpSession(this)
        }
        publishSession()
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = synchronized(this) {
            prefs.edit {
                putString(KEY_ACCESS_TOKEN, value)
                if (value != accessToken) bumpSession(this)
            }
            publishSession()
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

    var user: ScrobblerUser?
        get() = prefs.getString(KEY_USER, null)?.let {
            val lines = it.lines()
            if (lines.size != 4) {
                return@let null
            }
            val owner = lines[0].toLongOrNull() ?: return@let null
            val service = runCatching { ScrobblerService.valueOf(lines[3]) }.getOrNull()
                ?.takeIf { value -> value.name == libraryPrefix } ?: return@let null
            ScrobblerUser(
                id = owner,
                nickname = lines[1],
                avatar = lines[2].nullIfEmpty(),
                service = service,
            )
        }
        set(value) = synchronized(this) { prefs.edit {
            if (value?.id != user?.id) bumpSession(this)
            if (value == null) {
                remove(KEY_USER)
                return@edit
            }
            val str = StringJoiner("\n")
                .add(value.id)
                .add(value.nickname)
                .add(value.avatar.orEmpty())
                .add(value.service.name)
                .complete()
            putString(KEY_USER, str)
        }
        publishSession()
    }

    operator fun get(key: String): String? = prefs.getString(key, null)

    operator fun set(key: String, value: String?) = prefs.edit { putString(key, value) }

    fun clear() = synchronized(this) {
        progress.clearPending()
        val next = prefs.getLong(KEY_REVISION, 0L) + 1
        prefs.edit { clear(); putLong(KEY_REVISION, next) }
        changes.value = next
    }
}
