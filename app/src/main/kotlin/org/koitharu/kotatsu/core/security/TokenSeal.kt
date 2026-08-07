package org.koitharu.kotatsu.core.security

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Runtime half of the build-time Telegram backup "TokenSeal".
 *
 * The default Telegram bot token is never a plaintext constant in the app. At build time
 * the `generateTokenSeal` Gradle task seals it with AES-256-GCM under a fresh per-build key
 * and writes the ciphertext + split key parts + nonce + app tag into the generated [TokenData].
 * This object reassembles and decrypts on demand; the plaintext exists only for the duration of
 * a single call.
 *
 * Honest security note: this is *obfuscation*, not a secret store. The key material travels in
 * the same APK, so a determined reverse engineer can still recover the token. Its value is that
 * (a) the token is not a scanner-visible constant, (b) each release draws a fresh key & nonce so
 * ciphertext rotates ("time" factor), (c) the app tag is bound into the GCM AAD so the envelope is
 * tied to a specific app + build and any tamper/AAD mismatch fails authentication (the "identifier"
 * factor). Per-user tokens remain the stronger option (stored in encrypted preferences). Keep the
 * parameter contract below identical to the Groovy generator in `app/build.gradle`.
 */
object TokenSeal {

	private const val TRANSFORMATION = "AES/GCM/NoPadding"
	private const val TAG_BITS = 128
	private const val NONCE_BYTES = 12
	private const val AAD_PREFIX = "org.koitharu.kotatsu.backup:"
	private val TOKEN_PATTERN = Regex("^\\d+:[A-Za-z0-9_-]+$")

	internal data class Envelope(
		val enabled: Boolean,
		val keyParts: List<ByteArray>,
		val nonce: ByteArray,
		val ciphertext: ByteArray,
		val appTag: String,
	)

	/** Envelope rebuilt from the build-time generated [TokenData]. */
	internal fun defaultEnvelope(): Envelope {
		val data = TokenData
		return Envelope(
			enabled = data.ENABLED,
			keyParts = data.keyParts,
			nonce = data.nonce,
			ciphertext = data.ciphertext,
			appTag = data.appTag,
		)
	}

	/** Decrypt the sealed default token: null if unconfigured, invalid, or tampered. */
	fun decryptDefault(): String? = decrypt(defaultEnvelope())

	/**
	 * Seal a plaintext using the same GCM contract as the build task (used by tests and by the
	 * build task's parity check). Keys/nonces default to fresh CSPRNG values.
	 */
	internal fun encrypt(
		plaintext: String,
		appTag: String,
		key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
		nonce: ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) },
	): Envelope {
		require(key.size == 32) { "AES-256 requires a 32-byte key" }
		require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
		val ciphertext = transform(
			Cipher.ENCRYPT_MODE, key, nonce, appTag,
			plaintext.toByteArray(StandardCharsets.UTF_8),
		)
		return Envelope(
			enabled = true,
			keyParts = key.toList().chunked(8).map { it.toByteArray() },
			nonce = nonce,
			ciphertext = ciphertext,
			appTag = appTag,
		)
	}

	internal fun decrypt(envelope: Envelope): String? {
		if (!envelope.enabled) return null
		if (envelope.keyParts.size != 4 || envelope.keyParts.sumOf { it.size } != 32) return null
		return try {
			val key = envelope.keyParts.reduce { acc, part -> acc + part }
			val plain = transform(
				Cipher.DECRYPT_MODE, key, envelope.nonce, envelope.appTag, envelope.ciphertext,
			)
			val token = String(plain, StandardCharsets.UTF_8)
			if (token.matches(TOKEN_PATTERN)) token else null
		} catch (_: Exception) {
			null
		}
	}

	private fun transform(mode: Int, key: ByteArray, nonce: ByteArray, appTag: String, input: ByteArray): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
		cipher.updateAAD((AAD_PREFIX + appTag).toByteArray(StandardCharsets.UTF_8))
		return cipher.doFinal(input)
	}
}