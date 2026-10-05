package org.koitharu.kotatsu.scrobbling.discord.domain

enum class DiscordPresenceStatus(val value: String) {
    ONLINE("online"),
    IDLE("idle"),
    DND("dnd"),
    INVISIBLE("invisible");

    fun effective(isIdle: Boolean): String = if (this == ONLINE && isIdle) IDLE.value else value

    companion object {
        fun fromPreference(value: String?, legacyInvisible: Boolean = false): DiscordPresenceStatus =
            entries.firstOrNull { it.value == value }
                ?: if (value == null && legacyInvisible) INVISIBLE else ONLINE
    }
}
