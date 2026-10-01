package org.koitharu.kotatsu.core.util.ext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorDetailsTest {
    @Test fun quotedBearerAndKeysAreFullyRemoved() {
        val input = """{"authorization":"Bearer private-token", "api_key":"secret with spaces", "x-api-key":"custom-secret", "message":"upstream unavailable"}"""
        val safe = input.redactSecrets()
        listOf("private-token", "secret with spaces", "custom-secret").forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("upstream unavailable"))
    }

    @Test fun plainHeaderAndQueryTokensAreRemoved() {
        val safe = "Authorization: Bearer private-token\nhttps://example.test/?key=query-secret&model=test".redactSecrets()
        assertFalse(safe.contains("private-token"))
        assertFalse(safe.contains("query-secret"))
        assertTrue(safe.contains("model=test"))
    }
}
