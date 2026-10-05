package org.koitharu.kotatsu.scrobbling.discord.ui

import com.discord.oauth2rpc.structures.RichPresence

/** Asset resolution must not mutate the desired activity used by another status refresh. */
internal suspend fun mapDiscordOauthActivity(
    presence: RichPresence,
    isNsfw: Boolean,
    resolveImage: suspend (String, Boolean) -> String?,
): Map<String, Any?> {
    val mapped = RichPresence(presence.sessionId).apply {
        name = presence.name
        type = presence.type
        url = presence.url
        applicationId = presence.applicationId
        state = presence.state
        details = presence.details
        timestamps = presence.timestamps
        party = presence.party
        buttons = presence.buttons
        metadata = presence.metadata
        secrets = presence.secrets
        platform = presence.platform
        createdTimestamp = presence.createdTimestamp
        flags = presence.flags
        syncId = presence.syncId
        assets = presence.assets.toMutableMap()
    }
    // toJSON rejects unresolved external URLs; resolve them on the copy before serializing.
    mapped.assets["largeImage"] = presence.assets["largeImage"]?.let { resolveImage(it, isNsfw) }
    mapped.assets["smallImage"] = presence.assets["smallImage"]?.let { resolveImage(it, false) }
    return mapped.toJSON()
}
