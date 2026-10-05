package org.koitharu.kotatsu.scrobbling.discord.ui

import com.my.kizzyrpc.entities.presence.Presence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordTokenGatewayTest {
    @Test fun concurrentUpdatesWaitForOneConnectionAndReady() = runTest {
        val ready = CompletableDeferred<Unit>()
        val socket = FakeDiscordSocket(coroutineContext)
        val gateway = DiscordTokenGateway(socket, ready)
        val first = async { gateway.awaitReady() }
        val second = async { gateway.awaitReady() }
        runCurrent()
        assertEquals(1, socket.connections)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        ready.complete(Unit)
        first.await()
        second.await()
    }

    @Test fun firstInvisiblePresenceContainsNoTitleOrAssetsInSerializedPayload() = runTest {
        val socket = FakeDiscordSocket(coroutineContext)
        val gateway = DiscordTokenGateway(socket, CompletableDeferred(Unit))
        gateway.awaitReady()
        assertTrue(gateway.send(DiscordPresencePacket(emptyList(), "invisible", null, false)))
        val serialized = Json.encodeToJsonElement(Presence.serializer(), socket.presences.single()).jsonObject
        assertTrue(serialized.getValue("activities").jsonArray.isEmpty())
        assertEquals("invisible", serialized.getValue("status").jsonPrimitive.content)
        assertEquals(1, socket.presences.size)
    }

    @Test fun invalidationImmediatelyPreventsWritesBeforeSocketTeardown() = runTest {
        val socket = FakeDiscordSocket(coroutineContext)
        val gateway = DiscordTokenGateway(socket, CompletableDeferred(Unit))
        gateway.awaitReady()
        gateway.invalidate()
        assertFalse(gateway.send(DiscordPresencePacket(emptyList(), "online", null, false)))
        assertTrue(socket.presences.isEmpty())
        gateway.close()
        assertEquals(1, socket.closes)
    }

}
