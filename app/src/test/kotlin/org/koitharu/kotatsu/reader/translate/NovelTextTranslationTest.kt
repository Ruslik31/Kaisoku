package org.koitharu.kotatsu.reader.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.junit.Assert.*
import org.junit.Test

class NovelTextTranslationTest {
	@Test fun splittingIsLosslessBoundedAndDoesNotSendImagesToTheProvider() {
		val text = "  First paragraph.\n\n" + "слово 😀 ".repeat(1300) +
			"\n📷 [图片: zip:/private/book.cbz!/1.jpg]\n\nLast paragraph.  "
		val parts = NovelTextTranslation.parts(text)
		assertEquals(text, parts.joinToString("") { it.text })
		assertTrue(parts.filter { it.translate }.all { it.text.length <= 2500 })
		assertFalse(parts.filter { it.translate }.any { it.text.contains("zip:") })
		assertTrue(parts.filter { !it.translate }.any { it.text.contains("zip:") })
		val unbroken = "😀".repeat(3500)
		assertEquals(unbroken, NovelTextTranslation.parts(unbroken, 251).joinToString("") { it.text })
		assertTrue(NovelTextTranslation.parts(unbroken, 251).all { !it.text.last().isHighSurrogate() })
	}

	@Test fun googleTranslatesMixedLanguageParagraphsInParallelWithoutReordering() = runTest {
		val paragraphs = listOf("第一段", "Уже переведено.", "第二段", "Last paragraph")
		val calls = mutableListOf<String>()
		val progress = mutableListOf<Int>()
		var active = 0
		var peak = 0
		val translated = NovelTextTranslation.translateGoogle(
			paragraphs.joinToString("\n\n") + "\n📷 [图片: zip:/book/1.jpg]\n",
			{ done, total -> progress += done; assertEquals(4, total) },
		) { text ->
			calls += text
			active++
			peak = maxOf(peak, active)
			delay(if (text == paragraphs.first()) 100 else 10)
			active--
			"[$text]"
		}
		assertEquals(paragraphs.map { "[$it]" }.joinToString("\n\n") +
			"\n📷 [图片: zip:/book/1.jpg]\n", translated)
		assertEquals(paragraphs.toSet(), calls.toSet())
		assertEquals(3, peak)
		assertEquals((0..4).toList(), progress)
	}

	@Test fun failedGoogleWorkerCancelsPeersAndCannotReturnPartialChapter() = runTest {
		var cancelled = 0
		try {
			NovelTextTranslation.translateGoogle("first\nsecond\nthird", { _, _ -> }) { text ->
				if (text == "second") { delay(10); error("Network failure") }
				try { awaitCancellation() } finally { cancelled++ }
			}
			fail("Partial chapter was returned")
		} catch (e: IllegalStateException) {
			assertEquals("Network failure", e.message)
		}
		assertEquals(2, cancelled)
	}

	@Test fun cancelledGoogleTranslationStopsEveryWorker() = runTest {
		val started = CompletableDeferred<Unit>()
		var active = 0
		var cancelled = 0
		val job = launch {
			NovelTextTranslation.translateGoogle("first\nsecond\nthird\nfourth", { _, _ -> }) {
				if (++active == 3) started.complete(Unit)
				try { awaitCancellation() } finally { cancelled++ }
			}
		}
		started.await()
		job.cancelAndJoin()
		assertEquals(3, cancelled)
	}

	@Test fun apiTextChunksRunConcurrentlyAndAreJoinedInOriginalOrder() = runTest {
		val parts = listOf(
			NovelTextTranslation.Part("first", true),
			NovelTextTranslation.Part(" ", false),
			NovelTextTranslation.Part("second", true),
			NovelTextTranslation.Part("\n", false),
			NovelTextTranslation.Part("third", true),
		)
		val progress = mutableListOf<Int>()
		var active = 0
		var peak = 0
		val translated = NovelTextTranslation.translateParts(parts, { done, total ->
			progress += done
			assertEquals(3, total)
		}, concurrency = 3) { text ->
			active++
			peak = maxOf(peak, active)
			try {
				delay(if (text == "first") 100 else 10)
				"[$text]"
			} finally {
				active--
			}
		}

		assertEquals("[first] [second]\n[third]", translated)
		assertEquals(3, peak)
		assertEquals(listOf(0, 1, 2, 3), progress)
	}

	@Test fun providerPayloadsContainTextWithoutImageOrOcrInstructions() {
		val openai = NovelTextTranslation.payload("Read this", "auto", "ru", "model", false)
		assertEquals("Read this", openai.getJSONArray("messages").getJSONObject(1).getString("content"))
		assertFalse(openai.has("max_tokens"))
		assertFalse(openai.toString().contains("image_url"))
		val gemini = NovelTextTranslation.payload("Read this", "auto", "ru", "model", true)
		assertEquals("Read this", gemini.getJSONArray("contents").getJSONObject(0)
			.getJSONArray("parts").getJSONObject(0).getString("text"))
		assertFalse(gemini.getJSONObject("generationConfig").has("maxOutputTokens"))
		assertFalse(gemini.toString().contains("inline_data"))
	}

	@Test fun parsesBothProvidersAndRejectsTruncatedOrEmptyTranslations() {
		assertEquals("Привет", NovelTextTranslation.response("""{"choices":[{"finish_reason":"stop","message":{"content":"Привет"}}]}"""))
		assertEquals("Привет", NovelTextTranslation.response("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"hidden","thought":true},{"text":"Привет"}]}}]}"""))
		for (body in listOf("{}", "null", """{"choices":[{"finish_reason":"stop","message":{"content":null}}]}""")) {
			assertThrows(TranslateException.Parse::class.java) { NovelTextTranslation.response(body) }
		}
		for (body in listOf("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
			"""{"candidates":[{"finishReason":"SAFETY"}]}""")) {
			assertThrows(TranslateException.ProviderResponse::class.java) { NovelTextTranslation.response(body) }
		}
	}

	@Test fun geminiHttpErrorKeepsFullCopyableResponseAndRedactsApiKeys() {
		val body = """{"error":{"message":"The service is temporarily overloaded","status":"UNAVAILABLE","details":[{"debug":"full server detail"}],"api_key":"AIza012345678901234567890123456789"}}"""
		val error = TranslateException.Http(
			code = 503,
			responseBody = body,
			provider = "GEMINI",
			model = "gemini-test",
			requestId = "request-123",
			retryAfter = "3",
		)

		assertTrue(error.message.orEmpty().contains("503"))
		val copied = error.getCopyableErrorDetails()
		assertTrue(copied.contains("HTTP status: 503"))
		assertTrue(copied.contains("Provider: GEMINI"))
		assertTrue(copied.contains("request-123"))
		assertTrue(copied.contains("full server detail"))
		assertTrue(copied.contains("[REDACTED]"))
		assertFalse(copied.contains("AIza012345678901234567890123456789"))
	}
}
