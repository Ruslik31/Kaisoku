package org.koitharu.kotatsu.scrobbling.discord.ui

import com.discord.oauth2rpc.structures.RichPresence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiscordPresenceMappingTest {
    @Test fun resolvedPayloadDoesNotModifyTheLatestDesiredActivity() = runTest {
        val presence = RichPresence().setApplicationId("app").setDetails("chapter B")
            .setAssetsLargeImage("https://source/cover").setAssetsSmallImage("https://app/icon")
            .setAssetsLargeText("title").setStartTimestamp(123L)
        val resolved = ArrayList<Pair<String, Boolean>>()
        val first = mapDiscordOauthActivity(presence, true) { url, nsfw ->
            resolved.add(url to nsfw)
            "mp:$url"
        }
        assertEquals(listOf("https://source/cover" to true, "https://app/icon" to false), resolved)
        assertEquals("https://source/cover", presence.assets["largeImage"])
        assertEquals("https://app/icon", presence.assets["smallImage"])
        assertEquals("mp:https://source/cover", (first["assets"] as Map<*, *>)["large_image"])
        val second = mapDiscordOauthActivity(presence, false) { _, _ -> "second" }
        assertEquals("mp:https://source/cover", (first["assets"] as Map<*, *>)["large_image"])
        assertEquals("second", (second["assets"] as Map<*, *>)["large_image"])
        assertEquals("chapter B", second["details"])
        assertEquals(123L, (second["timestamps"] as Map<*, *>)["start"])
    }

    @Test fun unavailableImagesKeepTextAndOmitInvalidAssetValues() = runTest {
        val presence = RichPresence().setAssetsLargeImage("https://cover").setAssetsLargeText("title")
        val mapped = mapDiscordOauthActivity(presence, false) { _, _ -> null }
        val assets = mapped["assets"] as Map<*, *>
        assertEquals("title", assets["large_text"])
        assertFalse(assets.containsKey("large_image"))
        assertFalse(assets.containsKey("small_image"))
    }
}
