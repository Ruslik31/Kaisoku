package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Source diagnostics are separate from successful candidates and survive skipped cooldowns. */
internal class RecommendationFailures(previous: String?) {
    private val entries = decode(previous).associateByTo(linkedMapOf()) { it.source }
    private val failedThisRefresh = mutableSetOf<String>()
    private var legacy = previous?.takeIf { it.isNotBlank() && entries.isEmpty() }

    suspend fun <T> capture(
        source: String,
        name: String,
        onFailure: suspend () -> Unit = {},
        recordSuccess: Boolean = true,
        block: suspend () -> T,
    ): T? = try {
        block().also { if (recordSuccess) succeeded(source) }
    } catch (error: TimeoutCancellationException) {
        // A timeout belonging to the refresh/caller must still cancel all its children.
        currentCoroutineContext().ensureActive()
        failed(source, name, error, "Request timed out")
        onFailure()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        failed(source, name, error)
        onFailure()
        null
    }

    @Synchronized
    fun failed(source: String, name: String, error: Throwable, message: String? = error.message) {
        entries[source] = Entry(source, name, message ?: error.javaClass.simpleName, error.stackTraceToString())
        failedThisRefresh.add(source)
        legacy = null
    }

    @Synchronized
    private fun succeeded(source: String) {
        if (source !in failedThisRefresh) entries.remove(source)
        if (REFRESH !in failedThisRefresh) entries.remove(REFRESH)
        legacy = null
    }

    @Synchronized
    fun retainSources(sources: Set<String>) {
        entries.keys.retainAll(sources + REFRESH)
    }

    @Synchronized
    fun encode(): String? {
        if (entries.isEmpty()) return legacy
        val errors = entries.values.sortedBy { it.source }.map { entry ->
            JSONObject().put("source", entry.source).put("name", entry.name)
                .put("message", entry.message).put("details", entry.details)
        }
        return JSONObject().put("recommendationErrors", 1).put("entries", JSONArray(errors)).toString()
    }

    private data class Entry(val source: String, val name: String, val message: String, val details: String)

    companion object {
        const val REFRESH = "refresh"

        /** Old cached plaintext remains readable and copyable without a database migration. */
        fun summary(details: String): String = decode(details).takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { "${it.name}\n${it.message.lineSequence().firstOrNull().orEmpty().take(240)}" }
            ?: details

        fun fullDetails(details: String): String = decode(details).takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { "${it.name} (${it.source})\n${it.details}" } ?: details

        private fun decode(details: String?): List<Entry> {
            if (details.isNullOrBlank()) return emptyList()
            return runCatching {
                val json = JSONObject(details)
                if (json.optInt("recommendationErrors") != 1) return@runCatching emptyList()
                val errors = json.getJSONArray("entries")
                (0 until errors.length()).map { index ->
                    val entry = errors.getJSONObject(index)
                    Entry(entry.getString("source"), entry.getString("name"), entry.getString("message"),
                        entry.getString("details"))
                }
            }.getOrDefault(emptyList())
        }
    }
}
