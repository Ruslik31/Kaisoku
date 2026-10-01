package org.koitharu.kotatsu.sync.drive

import org.junit.Assert.*
import org.junit.Test

class NetworkSettingsMergePolicyTest {
    @Test fun disabledLocalChoicesArePreservedIncludingLegacyProxyAlias() {
        assertEquals(setOf("doh", "images_proxy_2", "images_proxy"),
            existingNetworkSettingsKeys(mapOf("doh" to "NONE", "images_proxy_2" to "-1")))
    }

    @Test fun legacyProxyChoiceCannotBeOverriddenByNewKey() {
        assertEquals(setOf("images_proxy", "images_proxy_2"),
            existingNetworkSettingsKeys(mapOf("images_proxy" to false)))
    }

    @Test fun freshInstallStillAcceptsNetworkSettingsAndOtherSettingsStillSync() {
        assertTrue(existingNetworkSettingsKeys(emptyMap<String, Any>()).isEmpty())
        assertTrue(existingNetworkSettingsKeys(mapOf("theme" to "dark")).isEmpty())
    }
}
