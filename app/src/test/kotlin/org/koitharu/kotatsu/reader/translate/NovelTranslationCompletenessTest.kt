package org.koitharu.kotatsu.reader.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelTranslationCompletenessTest {

	@Test fun geminiStopWithCopiedFirstChunkRecoversOnlyThatChunk() = runTest {
		val first = "这是需要完整翻译的第一段小说正文。".repeat(135)
		val second = "这是第二段小说内容。".repeat(60)
		val parts = NovelTextTranslation.parts("$first\n\n$second")
		assertEquals(2, parts.count { it.translate })
		val calls = mutableListOf<String>()
		val progress = mutableListOf<Int>()
		var active = 0
		var peak = 0
		val result = NovelTextTranslation.translateParts(parts, { done, total ->
			assertEquals(2, total)
			progress += done
		}, concurrency = 2) { chunk ->
			NovelTextTranslation.translateValidatedPart(chunk, "auto", "ru") { excerpt ->
				calls += excerpt
				active++
				peak = maxOf(peak, active)
				try {
					delay(10)
					val content = if (excerpt == first) excerpt else "Перевод ${excerpt.length}"
					NovelTextTranslation.response(geminiResponse(content))
				} finally { active-- }
			}
		}
		assertFalse(result.any { it in '\u4e00'..'\u9fff' })
		assertTrue(result.endsWith("Перевод ${second.length}"))
		assertEquals(1, calls.count { it == first })
		assertEquals(1, calls.count { it == second })
		assertTrue(calls.filter { it != first && it != second }.all { it.length <= 600 })
		assertTrue(calls.size > 2)
		assertEquals(2, peak)
		assertEquals(listOf(0, 1, 2), progress)
	}

	@Test fun copiedProseIsDetectedInsideAnOtherwiseTranslatedChunkDespiteFormattingChanges() {
		val passage = "这是一个没有被翻译的小说段落其中包括很长的完整对话和故事内容。"
		val original = "$passage\n\n这是另外的段落。"
		val partial = passage.chunked(7).joinToString(" ") + "\n\nЭто уже переведено."
		assertTrue(NovelTextTranslation.hasUntranslatedPassage(original, partial, "auto", "ru"))
		assertTrue(NovelTextTranslation.hasUntranslatedPassage(original, original, "ru", "ru"))
		assertFalse(NovelTextTranslation.hasUntranslatedPassage(original, "Это полностью переведённый отрывок.", "auto", "ru"))
	}

	@Test fun alreadyTargetLanguageAndShortProperNamesRemainValid() {
		val russian = "Этот абзац уже написан на русском языке и должен остаться таким же."
		assertFalse(NovelTextTranslation.hasUntranslatedPassage(russian, russian, "auto", "ru"))
		assertFalse(NovelTextTranslation.hasUntranslatedPassage("刘弈", "刘弈", "auto", "ru"))
		val withName = "$russian 刘弈. $russian 刘弈."
		assertFalse(NovelTextTranslation.hasUntranslatedPassage(withName, withName, "auto", "ru"))
		val english = "This paragraph is already in English and should remain unchanged."
		assertFalse(NovelTextTranslation.hasUntranslatedPassage(english, english, "auto", "en"))
		assertFalse(NovelTextTranslation.hasUntranslatedPassage(english, english, "en", "en"))
		assertTrue(NovelTextTranslation.hasUntranslatedPassage(english, english, "en", "es"))
		val name = "Maximilien François Marie Isidore de Robespierre"
		assertFalse(NovelTextTranslation.hasUntranslatedPassage(name, name, "en", "es"))
	}

	@Test fun repeatedUntranslatedResponseFailsWithAnActionableErrorAndCannotReturnPartialChapter() = runTest {
		val original = "这是一个不断被服务原样返回而没有完成翻译的小说段落。".repeat(40)
		val calls = mutableListOf<String>()
		try {
			NovelTextTranslation.translateParts(NovelTextTranslation.parts(original), { _, _ -> }, 2) { chunk ->
				NovelTextTranslation.translateValidatedPart(chunk, "auto", "ru") { excerpt ->
					calls += excerpt
					excerpt
				}
			}
			fail("Partial chapter was returned")
		} catch (error: TranslateException.UntranslatedText) {
			assertTrue(error.message.orEmpty().contains("another model"))
		}
		assertEquals(2, calls.size)
		assertTrue(calls.last().length <= 600)
	}

	@Test fun cancellingDuringRecoveryStopsTheRequestWithoutReturningOrCachingPartialText() = runTest {
		val recovering = CompletableDeferred<Unit>()
		val original = "这是尚未完成翻译并且正在等待重试响应的小说段落。".repeat(40)
		var calls = 0
		var cancelled = false
		val job = launch {
			NovelTextTranslation.translateValidatedPart(original, "auto", "ru") {
				if (++calls == 1) original else {
					recovering.complete(Unit)
					try { awaitCancellation() } finally { cancelled = true }
				}
			}
			fail("Cancelled translation returned")
		}
		recovering.await()
		job.cancelAndJoin()
		assertTrue(cancelled)
		assertEquals(2, calls)
	}

	@Test fun promptNamesTheTargetLanguageAndRequiresEveryParagraphAndDialogue() {
		val text = "第一段 **。\n\n第二段 ***。"
		for (gemini in listOf(true, false)) {
			val payload = NovelTextTranslation.payload(text, "auto", "ru", "model", gemini)
			val prompt = if (gemini) payload.getJSONObject("system_instruction").getJSONArray("parts")
				.getJSONObject(0).getString("text") else payload.getJSONArray("messages")
				.getJSONObject(0).getString("content")
			val input = if (gemini) payload.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
				.getJSONObject(0).getString("text") else payload.getJSONArray("messages")
				.getJSONObject(1).getString("content")
			assertEquals(text, input)
			assertTrue(prompt.contains("Russian (ru)"))
			assertTrue(prompt.contains("detect it automatically"))
			assertTrue(prompt.contains("every paragraph"))
			assertTrue(prompt.contains("quoted dialogue"))
			assertTrue(prompt.contains("Preserve censored placeholders"))
			assertTrue(prompt.contains("Leave omitted words omitted"))
		}
	}

	private fun geminiResponse(text: String): String = JSONObject().put("candidates", JSONArray().put(
		JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(
			JSONObject().put("text", text),
		))),
	)).toString()
}
