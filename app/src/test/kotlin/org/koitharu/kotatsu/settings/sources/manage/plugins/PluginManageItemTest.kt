package org.koitharu.kotatsu.settings.sources.manage.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManageItemTest {
    @Test
    fun displayRenameKeepsSourceAndUpdateIdentity() {
        val original = PluginManageItem.Plugin("uma.jar", "InvalidDavid/UMA", "old", "new")
        val renamed = original.copy(customName = "My plugin")
        assertEquals("My plugin", renamed.displayName)
        assertEquals(original.jarName, renamed.jarName)
        assertEquals(original.repository, renamed.repository)
        assertTrue(original.areItemsTheSame(renamed))
        assertTrue(renamed.hasUpdate)
    }

    @Test
    fun unlabelledPluginsKeepTheirExistingNames() {
        val plugin = PluginManageItem.Plugin("saved.jar", null, null, null)
        assertEquals("saved", plugin.displayName)
        assertEquals("saved", plugin.copy(customName = "").displayName)
    }
}
