package org.koitharu.kotatsu.scrobbling.anilist.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AniListLibraryChunksTest {
    private fun entry(id: Long) = JSONObject().put("id", id + 100_000).put("status", "PAUSED")
        .put("score", 85).put("progress", 40).put("notes", "Website notes")
        .put("media", JSONObject().put("id", id).put("title", JSONObject().put("userPreferred", "Title $id"))
            .put("coverImage", JSONObject()))

    private fun response(ids: List<Long>, hasNext: Boolean = false, owner: Long = 1, custom: List<Long> = emptyList()): JSONObject {
        val groups = JSONArray().put(JSONObject().put("entries", JSONArray(ids.map(::entry))))
        if (custom.isNotEmpty()) groups.put(JSONObject().put("entries", JSONArray(custom.map(::entry))))
        return JSONObject().put("data", JSONObject().put("MediaListCollection", JSONObject()
            .put("user", JSONObject().put("id", owner)).put("lists", groups).put("hasNextChunk", hasNext)))
    }

    @Test fun chunksIncludeCustomListsOnceAndPreserveRemoteFields() = runTest {
        val chunks = mutableListOf<Int>()
        val result = collectAniListLibraryChunks(1) { chunk ->
            chunks += chunk
            if (chunk == 1) response(listOf(1, 2), true, custom = listOf(2, 3))
            else response(listOf(3, 4))
        }
        assertEquals(listOf(1, 2), chunks)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.map { it.mediaId })
        assertEquals(40, result.first().progress); assertEquals("Website notes", result.first().notes)
        assertEquals(0.85f, ScoreFormat.POINT_100.normalize(result.first().score), 0.0001f)
    }

    @Test fun librariesCanPassThePagesFiveThousandEntryLimit() = runTest {
        val result = collectAniListLibraryChunks(1) { chunk ->
            response(List(500) { (chunk - 1) * 500L + it + 1 }, hasNext = chunk < 11)
        }
        assertEquals(5_500, result.size)
        assertEquals(5_500L, result.last().mediaId)
    }

    @Test fun wrongAccountsAndNonAdvancingChunksNeverBecomeSuccessfulSnapshots() = runTest {
        assertTrue(runCatching { collectAniListLibraryChunks(1) { response(listOf(1), owner = 2) } }.isFailure)
        assertTrue(runCatching { collectAniListLibraryChunks(1) { response(listOf(1), true) } }.isFailure)
        assertTrue(runCatching { collectAniListLibraryChunks(1) { response(emptyList(), true) } }.isFailure)
        assertEquals(emptyList<Any>(), collectAniListLibraryChunks(1) { response(emptyList()) })
    }

    @Test fun cancellationRejectsEvenANonCooperativeFinalResponse() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var returned = false
        val request = async {
            collectAniListLibraryChunks(1) {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    finish.await()
                    response(listOf(1))
                }
            }.also { returned = true }
        }
        started.await()
        request.cancel()
        finish.complete(Unit)
        request.join()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertFalse(returned)
    }
}
