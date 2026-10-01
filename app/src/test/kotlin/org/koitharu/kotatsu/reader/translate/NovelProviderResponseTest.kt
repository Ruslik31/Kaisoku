package org.koitharu.kotatsu.reader.translate

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails

class NovelProviderResponseTest {
	@Test fun geminiSafetyReasonAndFullFeedbackAreCopyableInsteadOfGenericParseError() = runTest {
		val body = candidate("SAFETY").put("responseId", "request-379")
			.put("modelVersion", "gemini-test").apply {
				getJSONArray("candidates").getJSONObject(0)
					.put("finishMessage", "Response blocked by content policy")
					.put("safetyRatings", JSONArray().put(JSONObject()
						.put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT").put("blocked", true)))
			}.toString()
		var calls = 0
		try {
			NovelTextTranslation.translateValidatedPart("ordinary fixture text".repeat(100), "en", "ru") {
				calls++
				NovelTextTranslation.response(body, "GEMINI", "gemini-test")
			}
			fail("Blocked content was accepted")
		} catch (e: TranslateException.ProviderResponse) {
			assertEquals(TranslateException.ProviderResponse.Outcome.BLOCKED, e.outcome)
			assertTrue(e.message.orEmpty().contains("SAFETY"))
			val details = e.getCopyableErrorDetails()
			assertTrue(details.contains("request-379"))
			assertTrue(details.contains("HARM_CATEGORY_SEXUALLY_EXPLICIT"))
			assertTrue(details.contains("Response blocked by content policy"))
			assertTrue(details.contains("Model: gemini-test"))
		}
		assertEquals(1, calls)
	}

	@Test fun promptBlockWithoutCandidatesAndOpenaiRefusalRemainProviderErrors() {
		val prompt = JSONObject().put("promptFeedback", JSONObject().put("blockReason", "PROHIBITED_CONTENT"))
		assertBlocked(prompt.toString(), "PROHIBITED_CONTENT")
		val openai = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
			.put("message", JSONObject().put("refusal", "Cannot fulfill request").put("content", "partial"))))
		assertBlocked(openai.toString(), "refusal")
		assertBlocked(candidate("RECITATION", "partial").toString(), "RECITATION")
		val inconsistent = candidate("STOP", "partial").apply {
			getJSONArray("candidates").getJSONObject(0).put("safetyRatings", JSONArray().put(
				JSONObject().put("category", "HARM_CATEGORY_DANGEROUS_CONTENT").put("blocked", true),
			))
		}
		assertBlocked(inconsistent.toString(), "SAFETY")
	}

	@Test fun truncatedChunkRetriesSmallerExcerptsWithoutDuplicatingPartialOutput() = runTest {
		for (gemini in listOf(true, false)) {
			val text = "中国小说正文需要完整翻译。".repeat(170)
			val calls = mutableListOf<String>()
			val output = NovelTextTranslation.translateValidatedPart(text, "auto", "ru") { excerpt ->
				calls += excerpt
				if (calls.size == 1) {
					val body = if (gemini) candidate("MAX_TOKENS", "discard this partial output") else
						JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "length")
							.put("message", JSONObject().put("content", "discard this partial output"))))
					NovelTextTranslation.response(body.toString())
				} else "Перевод ${calls.size}."
			}
			assertTrue(calls.drop(1).all { it.length <= 600 })
			assertEquals(text, calls.drop(1).joinToString(""))
			assertFalse(output.contains("partial"))
			assertEquals(calls.indices.drop(1).joinToString("") { "Перевод ${it + 1}." }, output)
		}
	}

	@Test fun repeatedTruncationStopsAfterOneSubdivisionAndDoesNotReturnIncompleteChapter() = runTest {
		var calls = 0
		try {
			NovelTextTranslation.translateValidatedPart("short fixture".repeat(10), "en", "ru") {
				calls++
				NovelTextTranslation.response(candidate("MAX_TOKENS", "partial").toString())
			}
			fail("Truncated output was accepted")
		} catch (e: TranslateException.ProviderResponse) {
			assertEquals(TranslateException.ProviderResponse.Outcome.OUTPUT_LIMIT, e.outcome)
		}
		assertEquals(2, calls)
	}

	@Test fun unknownFinishReasonIsRetainedWithoutAutomaticRetryAndSecretsAreRedacted() = runTest {
		var calls = 0
		val body = candidate("OTHER").put("api_key", "private-key-value").toString()
		try {
			NovelTextTranslation.translateValidatedPart("fixture".repeat(100), "en", "ru") {
				calls++
				NovelTextTranslation.response(body)
			}
			fail("Unknown finish was accepted")
		} catch (e: TranslateException.ProviderResponse) {
			assertEquals(TranslateException.ProviderResponse.Outcome.OTHER, e.outcome)
			assertEquals("OTHER", e.reason)
			assertFalse(e.getCopyableErrorDetails().contains("private-key-value"))
		}
		assertEquals(1, calls)
	}

	@Test fun completeGeminiResponseStillJoinsTextAndExcludesThoughts() {
		val body = candidate("STOP", "Первая часть").apply {
			getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
				.put(JSONObject().put("thought", true).put("text", "private reasoning"))
				.put(JSONObject().put("text", ". Вторая часть."))
		}
		assertEquals("Первая часть. Вторая часть.", NovelTextTranslation.response(body.toString()))
	}

	private fun assertBlocked(body: String, reason: String) {
		try {
			NovelTextTranslation.response(body)
			fail("Blocked content was accepted")
		} catch (e: TranslateException.ProviderResponse) {
			assertEquals(reason, e.reason)
			assertEquals(TranslateException.ProviderResponse.Outcome.BLOCKED, e.outcome)
		}
	}

	private fun candidate(reason: String, text: String = "") = JSONObject().put("candidates", JSONArray().put(
		JSONObject().put("finishReason", reason).put("content", JSONObject().put("parts", JSONArray().put(
			JSONObject().put("text", text),
		))),
	))
}
