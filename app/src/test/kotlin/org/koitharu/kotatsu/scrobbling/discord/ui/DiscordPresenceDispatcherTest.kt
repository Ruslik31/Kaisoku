package org.koitharu.kotatsu.scrobbling.discord.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.discord.domain.DiscordPresenceStatus

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordPresenceDispatcherTest {
    @Test fun statusChangeUsesNewestPendingChapterForBothTransports() = runTest {
        for (debounce in listOf(3_000L, 16_000L)) {
            val fixture = Fixture(this, debounce)
            fixture.dispatcher.submit("chapter A", false)
            runCurrent()
            fixture.dispatcher.submit("chapter B", false)
            runCurrent()
            assertEquals(1, fixture.packets.size)
            fixture.choice = DiscordPresenceStatus.DND
            fixture.dispatcher.refresh()
            runCurrent()
            assertEquals(listOf("chapter B"), fixture.packets.last().activities)
            assertEquals("dnd", fixture.packets.last().status)
            assertEquals(testScheduler.currentTime, fixture.times.last())
            fixture.dispatcher.clear()
        }
    }

    @Test fun ordinaryUpdatesKeepExistingChapterDebounceAndCoalesce() = runTest {
        val fixture = Fixture(this, 16_000L)
        fixture.dispatcher.submit("A", false)
        runCurrent()
        fixture.dispatcher.submit("B", false)
        runCurrent()
        advanceTimeBy(1_000L)
        fixture.dispatcher.submit("C", false)
        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(1, fixture.packets.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("A", "C"), fixture.packets.flatMap { it.activities })
    }

    @Test fun coldInvisibleNeverPreparesOrSendsActivityAndCanRestoreNewestTitle() = runTest {
        val fixture = Fixture(this)
        fixture.choice = DiscordPresenceStatus.INVISIBLE
        fixture.dispatcher.submit("private title", false)
        runCurrent()
        assertEquals(0, fixture.prepared)
        assertTrue(fixture.packets.single().activities.isEmpty())
        assertEquals("invisible", fixture.packets.single().status)
        fixture.dispatcher.submit("new title", false)
        fixture.choice = DiscordPresenceStatus.ONLINE
        fixture.dispatcher.refresh()
        runCurrent()
        assertEquals(listOf("new title"), fixture.packets.last().activities)
    }

    @Test fun hidingDuringCoverPreparationCancelsVisiblePacket() = runTest {
        val fixture = Fixture(this)
        val cover = CompletableDeferred<Unit>()
        fixture.prepare = { cover.await(); it }
        fixture.dispatcher.submit("private title", false)
        runCurrent()
        fixture.choice = DiscordPresenceStatus.INVISIBLE
        fixture.dispatcher.refresh()
        runCurrent()
        cover.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.packets.size)
        assertTrue(fixture.packets.single().activities.isEmpty())
    }

    @Test fun activityClearedDuringConnectionCannotBeRevivedByReadyOrStatusChange() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        fixture.connect = { ready.await() }
        fixture.dispatcher.submit("private title", false)
        runCurrent()
        fixture.dispatcher.clear()
        fixture.dispatcher.refresh()
        ready.complete(Unit)
        runCurrent()
        assertNull(fixture.dispatcher.activity)
        assertTrue(fixture.packets.isEmpty())
    }

    @Test fun readyAndResumedKeepIdleTimestampAndExplicitStatus() = runTest {
        val fixture = Fixture(this)
        fixture.dispatcher.submit("A", false)
        runCurrent()
        assertNull(fixture.packets.single().since)
        fixture.dispatcher.setIdle()
        runCurrent()
        val idle = fixture.packets.last()
        assertEquals("idle", idle.status)
        assertEquals(1_700_000_000_000L, idle.since)
        assertTrue(idle.afk)
        advanceTimeBy(1_000L)
        fixture.choice = DiscordPresenceStatus.DND
        fixture.dispatcher.refresh()
        runCurrent()
        assertEquals("dnd", fixture.packets.last().status)
        assertNull(fixture.packets.last().since)
        fixture.choice = DiscordPresenceStatus.ONLINE
        fixture.dispatcher.refresh()
        runCurrent()
        assertEquals(idle.since, fixture.packets.last().since)
        fixture.dispatcher.submit("B", false)
        fixture.dispatcher.refresh()
        runCurrent()
        assertEquals("online", fixture.packets.last().status)
        assertFalse(fixture.packets.last().afk)
    }

    @Test fun rapidStatusChangesRespectGatewayLimitAndSendOnlyNewestWaitingActivity() = runTest {
        val fixture = Fixture(this)
        repeat(5) {
            fixture.dispatcher.submit("$it", false)
            fixture.dispatcher.refresh()
            runCurrent()
        }
        fixture.dispatcher.submit("superseded", false)
        fixture.dispatcher.refresh()
        runCurrent()
        fixture.dispatcher.submit("latest", false)
        fixture.dispatcher.refresh()
        runCurrent()
        assertEquals(5, fixture.packets.size)
        advanceTimeBy(19_999L)
        runCurrent()
        assertEquals(5, fixture.packets.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(6, fixture.packets.size)
        assertEquals(listOf("latest"), fixture.packets.last().activities)
    }

    @Test fun changedPrivacyPolicyRejectsPendingNsfwWithoutAnError() = runTest {
        val fixture = Fixture(this)
        val cover = CompletableDeferred<Unit>()
        fixture.prepare = { cover.await(); it }
        fixture.dispatcher.submit("adult title", true)
        runCurrent()
        fixture.allowed = false
        cover.complete(Unit)
        runCurrent()
        assertTrue(fixture.packets.isEmpty())
        assertTrue(fixture.errors.isEmpty())
    }

    @Test fun failedTransportDoesNotKillLaterUpdatesAndCancelledMappingIsNotAnError() = runTest {
        val fixture = Fixture(this)
        fixture.failure = IOException("offline")
        fixture.dispatcher.submit("A", false)
        runCurrent()
        assertEquals(1, fixture.errors.size)
        fixture.failure = null
        fixture.dispatcher.submit("B", false)
        runCurrent()
        assertEquals(listOf("B"), fixture.packets.single().activities)
        val cover = CompletableDeferred<Unit>()
        fixture.prepare = { cover.await(); it }
        fixture.dispatcher.submit("C", false)
        fixture.dispatcher.refresh()
        runCurrent()
        fixture.dispatcher.clear()
        runCurrent()
        assertEquals(1, fixture.errors.size)
    }

    private class Fixture(scope: TestScope, debounce: Long = 3_000L) {
        var choice = DiscordPresenceStatus.ONLINE
        var allowed = true
        var connect: suspend () -> Unit = {}
        var prepare: suspend (String) -> String = { it }
        var failure: Exception? = null
        var prepared = 0
        val packets = ArrayList<DiscordPresencePacket<String>>()
        val times = ArrayList<Long>()
        val errors = ArrayList<Exception>()
        val dispatcher = DiscordPresenceDispatcher(
            scope = scope.backgroundScope,
            debounceMillis = debounce,
            now = { scope.testScheduler.currentTime },
            wallTime = { 1_700_000_000_000L + scope.testScheduler.currentTime },
            status = { choice },
            allowed = { allowed },
            connect = { connect(); Unit },
            prepare = { text: String, _: Boolean -> prepared++; prepare(text) },
            send = { _: Unit, packet ->
                failure?.let { throw it }
                packets.add(packet)
                times.add(scope.testScheduler.currentTime)
                true
            },
            onError = { errors.add(it) },
        )
    }
}
