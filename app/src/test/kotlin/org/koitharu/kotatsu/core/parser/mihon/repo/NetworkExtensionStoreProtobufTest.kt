package org.koitharu.kotatsu.core.parser.mihon.repo

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Decodes the *live* keiyoushi `/repo/index.pb` (fetched at the time the test suite runs in CI)
 * with the exact protobuf model the app uses. The fixture is injected as a JVM property so the
 * test stays hermetic; without it the test is skipped (fixture-fetching belongs to a network
 * integration test, not unit).
 */
class NetworkExtensionStoreProtobufTest {

	@Test
	fun decodesRealKeiyoushiIndexPb() {
		val path = System.getProperty("keiyoushi.index.pb") ?: return
		val raw = File(path).readBytes()
		val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
			GZIPInputStream(raw.inputStream()).use { it.readBytes() }
		} else {
			raw
		}
		val store = ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
		assertTrue(store.name.isNotBlank())
		assertTrue(store.extensionList != null && store.extensionList.extensions.isNotEmpty())
		val first = store.extensionList!!.extensions.first()
		assertTrue(first.packageName.startsWith("eu.kanade.tachiyomi.extension."))
		assertTrue(first.extensionLib.isNotBlank())
		assertTrue(first.versionCode > 0)
	}
}
