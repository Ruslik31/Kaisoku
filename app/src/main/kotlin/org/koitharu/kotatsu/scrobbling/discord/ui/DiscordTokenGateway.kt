package org.koitharu.kotatsu.scrobbling.discord.ui

import com.my.kizzyrpc.entities.presence.Activity
import com.my.kizzyrpc.entities.presence.Presence
import com.my.kizzyrpc.websocket.DiscordWebSocket
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Uses the pinned library's public socket API, including empty activity lists on first connection. */
internal class DiscordTokenGateway(
    private val socket: DiscordWebSocket,
    private val ready: CompletableDeferred<Unit>,
) {
    private val mutex = Mutex()
    private var started = false
    @Volatile private var closed = false

    suspend fun awaitReady() {
        check(!closed)
        mutex.withLock {
            check(!closed)
            if (!started) {
                socket.connect()
                started = true
            }
        }
        val connected = withTimeoutOrNull(30_000L) { ready.await(); true } ?: false
        if (!connected) throw IOException("Discord gateway connection timed out")
        check(!closed)
    }

    suspend fun send(packet: DiscordPresencePacket<Activity>): Boolean {
        if (closed) return false
        socket.sendActivity(Presence(packet.activities, packet.afk, packet.since, packet.status))
        return !closed
    }

    fun invalidate() {
        closed = true
        ready.cancel()
    }

    fun close() {
        invalidate()
        socket.close()
    }

    companion object {
        fun create(token: String, scope: CoroutineScope, onReady: () -> Unit): DiscordTokenGateway {
            val ready = CompletableDeferred<Unit>()
            val socket = DiscordTokenSocket(token, SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO) {
                ready.complete(Unit)
                onReady()
            }
            return DiscordTokenGateway(socket, ready)
        }
    }
}
