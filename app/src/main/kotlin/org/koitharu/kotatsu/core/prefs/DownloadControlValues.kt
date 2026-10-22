package org.koitharu.kotatsu.core.prefs

/** Accepted ListPreference wire values; absent or damaged values retain the existing download behavior. */
object DownloadControlValues {

    private val requestIntervals = setOf("0", "1600", "3000", "5000")
    private val chapterDelays = setOf("0", "1000", "3000", "5000", "10000", "30000")
    private val chapterLimits = setOf("0", "1", "2", "3", "4")

    fun requestInterval(raw: Any?): Long = validated(raw, requestIntervals).toLong()

    fun chapterDelay(raw: Any?): Long = validated(raw, chapterDelays).toLong()

    fun chapterConcurrency(raw: Any?): Int = validated(raw, chapterLimits).toInt()

    fun effectiveRequestInterval(globalInterval: Long, sourceSlowdownEnabled: Boolean): Long =
        maxOf(globalInterval, if (sourceSlowdownEnabled) SOURCE_REQUEST_INTERVAL else 0L)

    private fun validated(raw: Any?, values: Set<String>): String =
        (raw as? String)?.takeIf { it in values } ?: "0"

    private const val SOURCE_REQUEST_INTERVAL = 1_600L
}
