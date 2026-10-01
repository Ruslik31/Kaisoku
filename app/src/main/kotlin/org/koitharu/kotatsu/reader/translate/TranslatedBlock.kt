package org.koitharu.kotatsu.reader.translate

import android.graphics.Bitmap
import android.graphics.RectF
import org.json.JSONObject
import org.koitharu.kotatsu.core.exceptions.CopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.redactSecrets

/**
 * A single text block returned by the multimodal translator.
 *
 * @param rect normalised image-space rectangle (0..1 on each axis).
 */
data class TranslatedBlock(
	val originalText: String,
	val translatedText: String,
	val rect: RectF,
)

/** Aggregate translation progress across active requests (tiles completed / total). */
data class TranslationProgress(val done: Int, val total: Int)

sealed class TranslateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
	class NoEndpoint : TranslateException("Translation endpoint is not configured")
	class NoKey : TranslateException("Translation API key is not configured")
	class Http(
		val code: Int,
		val responseBody: String,
		val provider: String? = null,
		val model: String? = null,
		val requestId: String? = null,
		val retryAfter: String? = null,
		) : TranslateException("HTTP $code: ${providerMessage(responseBody).redactSecrets().take(240)}"), CopyableErrorDetails {
		override val copyableErrorDetails: String
			get() = buildString {
				appendLine("Translation provider HTTP error")
				appendLine("HTTP status: $code")
				provider?.let { appendLine("Provider: $it") }
				model?.let { appendLine("Model: $it") }
				requestId?.let { appendLine("Request ID: $it") }
				retryAfter?.let { appendLine("Retry-After: $it") }
				appendLine("Response body:")
				append(responseBody.ifBlank { "<empty>" })
			}.redactSecrets()

		private companion object {
			fun providerMessage(body: String): String {
				val json = runCatching { JSONObject(body) }.getOrNull()
				val error = json?.optJSONObject("error")
				val message = error?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
				val status = error?.optString("status")?.takeIf { it.isNotBlank() && it != "null" }
				return when {
					message != null && status != null -> "$message ($status)"
					message != null -> message
					else -> body.trim().ifBlank { "empty response body" }
				}
			}
		}
	}
	class Parse(reason: String, cause: Throwable? = null) : TranslateException("Failed to parse translator response: $reason", cause)
	class ProviderResponse(
		val reason: String,
		val outcome: Outcome,
		val responseBody: String,
		val provider: String,
		val model: String?,
	) : TranslateException(when (outcome) {
		Outcome.OUTPUT_LIMIT -> "Translation exceeded the provider output limit ($reason). Try another model if retry fails."
		Outcome.BLOCKED -> "Translation was blocked by the provider ($reason). Copy the error for details."
		Outcome.OTHER -> "Translation provider did not complete the response ($reason). Copy the error for details."
	}), CopyableErrorDetails {
		enum class Outcome { OUTPUT_LIMIT, BLOCKED, OTHER }

		override val copyableErrorDetails: String
			get() = buildString {
				appendLine(message)
				appendLine("Provider: $provider")
				model?.let { appendLine("Model: $it") }
				appendLine("Outcome: $outcome")
				appendLine("Provider reason: $reason")
				appendLine("Response body:")
				append(responseBody)
			}.redactSecrets()
	}
	class Network(cause: Throwable) : TranslateException("Network error: ${cause.message}", cause)
	class UntranslatedText : TranslateException(
		"Translation is incomplete: the provider left a passage untranslated. Retry or try another model.",
	)
	class Partial(val failedTiles: Int) : TranslateException("$failedTiles part(s) failed to translate")
}

sealed interface PageTranslationState {
	data object Idle : PageTranslationState
	data object Loading : PageTranslationState
	data class Done(
		val rendered: Bitmap,
		val blocks: List<TranslatedBlock>,
		val overflow: List<TranslationOverflow> = emptyList(),
		val isPartial: Boolean = false,
		val preRendered: Boolean = false,
	) : PageTranslationState
	data class Failed(val error: Throwable) : PageTranslationState
}
