package org.koitharu.kotatsu.scrobbling.discord.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.scrobbling.discord.domain.DiscordPresenceStatus

internal data class DiscordPresencePacket<T>(
    val activities: List<T>,
    val status: String,
    val since: Long?,
    val afk: Boolean,
)

/** Keep desired activity before any delay; a status change never restores the last sent chapter. */
internal class DiscordPresenceDispatcher<T, R, C>(
    scope: CoroutineScope,
    private val debounceMillis: Long,
    private val now: () -> Long,
    private val wallTime: () -> Long,
    private val status: () -> DiscordPresenceStatus,
    private val allowed: (Boolean) -> Boolean,
    private val connect: suspend () -> C?,
    private val prepare: suspend (T, Boolean) -> R,
    private val send: suspend (C, DiscordPresencePacket<R>) -> Boolean,
    private val onError: (Exception) -> Unit,
) {
    private data class Desired<T>(
        val activity: T,
        val isNsfw: Boolean,
        val idleSince: Long?,
        val urgent: Boolean,
        val revision: Long,
    )

    private val changes = MutableStateFlow<Desired<T>?>(null)
    private var revision = 0L
    private var lastSent: Long? = null
    private val sentAt = ArrayDeque<Long>()

    val activity: T? get() = changes.value?.activity
    val isNsfw: Boolean get() = changes.value?.isNsfw == true

    init {
        scope.launch {
            changes.collectLatest { desired ->
                if (desired == null) return@collectLatest
                try {
                    if (!allowed(desired.isNsfw)) return@collectLatest
                    if (!desired.urgent) {
                        lastSent?.let { delay((it + debounceMillis - now()).coerceAtLeast(0)) }
                    }
                    val choice = status()
                    val client = connect() ?: return@collectLatest
                    // Hiding never uploads covers or passes activity data to the transport.
                    val activities = if (choice == DiscordPresenceStatus.INVISIBLE) emptyList()
                    else listOf(prepare(desired.activity, desired.isNsfw))
                    while (true) {
                        val timestamp = now()
                        while (sentAt.firstOrNull()?.let { it <= timestamp - RATE_WINDOW } == true) {
                            sentAt.removeFirst()
                        }
                        if (sentAt.size < RATE_COUNT) break
                        delay((sentAt.first() + RATE_WINDOW - timestamp).coerceAtLeast(1))
                    }
                    currentCoroutineContext().ensureActive()
                    if (changes.value?.revision != desired.revision || !allowed(desired.isNsfw)) {
                        return@collectLatest
                    }
                    if (choice != status()) {
                        refresh()
                        return@collectLatest
                    }
                    val effectiveStatus = choice.effective(desired.idleSince != null)
                    val packet = DiscordPresencePacket(
                        activities = activities,
                        status = effectiveStatus,
                        since = if (effectiveStatus == DiscordPresenceStatus.IDLE.value) {
                            desired.idleSince ?: wallTime()
                        } else null,
                        afk = desired.idleSince != null,
                    )
                    // Reserve before sending: a cancelled/failed write must not evade the gateway limit.
                    sentAt.addLast(now())
                    if (send(client, packet)) lastSent = now()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (changes.value?.revision == desired.revision) onError(e)
                }
            }
        }
    }

    @Synchronized
    fun submit(activity: T, isNsfw: Boolean) {
        changes.value = Desired(activity, isNsfw, null, false, ++revision)
    }

    @Synchronized
    fun setIdle() {
        val desired = changes.value ?: return
        changes.value = desired.copy(idleSince = desired.idleSince ?: wallTime(), urgent = true, revision = ++revision)
    }

    @Synchronized
    fun refresh() {
        val desired = changes.value ?: return
        changes.value = desired.copy(urgent = true, revision = ++revision)
    }

    @Synchronized
    fun clear() {
        changes.value = null
        revision++
    }

    private companion object {
        const val RATE_WINDOW = 20_000L
        const val RATE_COUNT = 5
    }
}
