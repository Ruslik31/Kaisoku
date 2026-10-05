package org.koitharu.kotatsu.scrobbling.discord.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordPresenceStatusTest {
    @Test fun absentAndInvalidSettingsUseOnline() {
        for (value in listOf(null, "", "busy", "ONLINE")) {
            assertEquals(DiscordPresenceStatus.ONLINE, DiscordPresenceStatus.fromPreference(value))
        }
    }

    @Test fun legacyInvisibleOnlyAppliesWhenNewSettingIsAbsent() {
        assertEquals(DiscordPresenceStatus.INVISIBLE, DiscordPresenceStatus.fromPreference(null, true))
        assertEquals(DiscordPresenceStatus.ONLINE, DiscordPresenceStatus.fromPreference("online", true))
        assertEquals(DiscordPresenceStatus.DND, DiscordPresenceStatus.fromPreference("dnd", true))
    }

    @Test fun automaticIdlePreservesExplicitChoices() {
        assertEquals("online", DiscordPresenceStatus.ONLINE.effective(false))
        assertEquals("idle", DiscordPresenceStatus.ONLINE.effective(true))
        for (choice in listOf(DiscordPresenceStatus.IDLE, DiscordPresenceStatus.DND, DiscordPresenceStatus.INVISIBLE)) {
            assertEquals(choice.value, choice.effective(false))
            assertEquals(choice.value, choice.effective(true))
        }
    }
}
