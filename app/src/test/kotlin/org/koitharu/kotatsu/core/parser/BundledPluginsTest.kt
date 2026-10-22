package org.koitharu.kotatsu.core.parser

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class BundledPluginsTest {

	@Test
	fun everyBundledPluginShipsAsADexJarWithAnUpdateOrigin() {
		for (plugin in BundledPluginsInstaller.BUNDLED) {
			val file = File("src/main/assets/plugins/${plugin.fileName}")
			assertTrue("Missing asset ${file.path}", file.isFile)
			ZipFile(file).use { zip -> assertNotNull(zip.getEntry("classes.dex")) }
			assertTrue(plugin.repository.matches(Regex("""[\w.-]+/[\w.-]+""")))
			assertTrue(plugin.tag.isNotBlank())
		}
	}
}
