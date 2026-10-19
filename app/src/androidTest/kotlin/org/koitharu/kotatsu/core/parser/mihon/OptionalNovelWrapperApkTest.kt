package org.koitharu.kotatsu.core.parser.mihon

import androidx.preference.PreferenceScreen
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Read-only APK ABI fixture. It neither installs an APK nor initializes a foreign runtime. */
class OptionalNovelWrapperApkTest {
    @Test fun publishedWrapperClassesShareTheHostSourcePreferenceAndTextContracts() {
        val arguments = InstrumentationRegistry.getArguments()
        val directory = arguments.getString("novelWrapperApkDirectory")?.let(::File)
        assumeTrue("Optional APK fixture directory was not supplied", directory?.isDirectory == true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((slug, version, settingsClass) in listOf(
            Triple("shosetsu", "1.4.9", "Shosetsu"), Triple("ireader", "1.4.5", "IReader"),
        )) {
            val apk = File(directory, "tsundoku-all.$slug-v$version.apk")
            assertTrue("Missing optional wrapper fixture: ${apk.name}", apk.isFile)
            val loader = ChildFirstPathClassLoader(apk.absolutePath, null, context.classLoader)
            val prefix = "eu.kanade.tachiyomi.novelextension.all.$slug."
            assertTrue(SourceFactory::class.java.isAssignableFrom(loader.loadClass(prefix + settingsClass + "Factory")))
            val root = loader.loadClass(prefix + settingsClass + "Settings")
            assertTrue(ConfigurableSource::class.java.isAssignableFrom(root))
            assertTrue(NovelSource::class.java.isAssignableFrom(root))
            assertSame(PreferenceScreen::class.java,
                root.getMethod("setupPreferenceScreen", PreferenceScreen::class.java).parameterTypes.single())
            assertSame(Page::class.java, root.getMethod("fetchPageText", Page::class.java,
                kotlin.coroutines.Continuation::class.java).parameterTypes.first())
            assertSame(ConfigurableSource::class.java, loader.loadClass(ConfigurableSource::class.java.name))
            assertSame(NovelSource::class.java, loader.loadClass(NovelSource::class.java.name))
            // Optional foreign runtime dependencies come from this APK only when absent in host.
            assertNotNull(loader.loadClass("kotlinx.datetime.LocalDate"))
            if (slug == "ireader") assertNotNull(loader.loadClass("kotlinx.io.Buffer"))
        }
    }
}
