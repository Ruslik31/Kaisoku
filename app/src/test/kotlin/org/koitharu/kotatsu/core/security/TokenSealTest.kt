package org.koitharu.kotatsu.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TokenSealTest {

	private companion object {
		const val TOKEN = "123456789:AA_TEST_bot_TOKEN"
		private val TOKEN_PATTERN = Regex("^\\d+:[A-Za-z0-9_-]+$")
	}

	private fun fixedKey() = ByteArray(32) { it.toByte() }.toList().chunked(8).map { it.toByteArray() }

	@Test
	fun `round trip returns the original token`() {
		val envelope = TokenSeal.encrypt(TOKEN, appTag = "unit-test")
		assertEquals(TOKEN, TokenSeal.decrypt(envelope))
	}

	@Test
	fun `disabled envelope returns null`() {
		val envelope = TokenSeal.encrypt(TOKEN, appTag = "abc").copy(enabled = false)
		assertNull(TokenSeal.decrypt(envelope))
	}

	@Test
	fun `tampered ciphertext returns null`() {
		val envelope = TokenSeal.encrypt(TOKEN, appTag = "abc")
		val tamperedCipher = envelope.ciphertext.copyOf()
		tamperedCipher[0] = (tamperedCipher[0].toInt() xor 0x01).toByte()
		assertNull(TokenSeal.decrypt(envelope.copy(ciphertext = tamperedCipher)))
	}

	@Test
	fun `app tag aad mismatch returns null`() {
		val envelope = TokenSeal.encrypt(TOKEN, appTag = "appa").copy(appTag = "appb")
		assertNull(TokenSeal.decrypt(envelope))
	}

	@Test
	fun `wrong key returns null`() {
		val envelope = TokenSeal.encrypt(TOKEN, appTag = "abc").copy(keyParts = fixedKey())
		assertNull(TokenSeal.decrypt(envelope))
	}

	@Test
	fun `non token format plaintext returns null`() {
		val envelope = TokenSeal.encrypt("not-a-telegram-token", appTag = "abc")
		assertNull(TokenSeal.decrypt(envelope))
	}

	@Test
	fun `generated default token round trips and format validation is consistent`() {
		assumeTrue(TokenData.ENABLED)
		val env = TokenSeal.defaultEnvelope()

		// Open the build-time ciphertext directly with the stored constants: this proves the
		// Gradle (Groovy) sealing and the Kotlin runtime agree byte-for-byte on key split,
		// nonce and AAD.
		val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(
			javax.crypto.Cipher.DECRYPT_MODE,
			javax.crypto.spec.SecretKeySpec(env.keyParts.reduce { a, b -> a + b }, "AES"),
			javax.crypto.spec.GCMParameterSpec(128, env.nonce),
		)
		cipher.updateAAD(
			("org.koitharu.kotatsu.backup:" + env.appTag).toByteArray(StandardCharsets.UTF_8),
		)
		val raw = String(cipher.doFinal(env.ciphertext), StandardCharsets.UTF_8)
		assertEquals("configured token must not be empty", false, raw.isEmpty())

		// decryptDefault surfaces the plaintext only when it looks like a Telegram bot token;
		// a non-token placeholder configured in local.properties keeps the feature disabled.
		assertEquals(raw.takeIf { it.matches(TOKEN_PATTERN) }, TokenSeal.decryptDefault())
	}
}