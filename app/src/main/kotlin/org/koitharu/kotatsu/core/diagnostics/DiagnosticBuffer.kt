package org.koitharu.kotatsu.core.diagnostics

/** No free-text payloads: titles, paths, bodies and credentials cannot enter the recorder. */
internal enum class DiagnosticEvent { PROCESS, SCREEN_OPEN, SCREEN_CLOSE, HTTP, ERROR, CRASH, IMPORT }
internal enum class DiagnosticArea { APP, READER, NOVEL, DETAILS, SOURCES, SETTINGS, DOWNLOADS, OTHER }
internal class DiagnosticBuffer(private val limit: Int = 256) {
    private val entries = ArrayDeque<String>()
    @Synchronized fun add(time: Long, event: DiagnosticEvent, area: DiagnosticArea, status: Int, duration: Long, fingerprint: Int) {
        entries.addLast("$time $event $area status=$status ms=${duration.coerceIn(0, 86_400_000)} code=$fingerprint")
        while (entries.size > limit) entries.removeFirst()
    }
    @Synchronized fun drain(): String = entries.joinToString("\n").also { entries.clear() }
    @Synchronized fun snapshot(): String = entries.joinToString("\n")
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun isEmpty() = entries.isEmpty()
}
