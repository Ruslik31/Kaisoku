package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import java.text.Normalizer
import java.util.Locale

internal object ReplacementMatchPolicy {

	const val MIN_SIMILARITY = 0.92f
	const val MIN_MARGIN = 0.05f

	fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
		.lowercase(Locale.ROOT)
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.trim()
		.replace(Regex("\\s+"), " ")

	fun similarity(first: String, second: String): Float {
		val a = normalize(first)
		val b = normalize(second)
		val maxLength = maxOf(a.length, b.length)
		return if (maxLength == 0) 1f else 1f - a.levenshteinDistance(b).toFloat() / maxLength
	}

	fun isExact(candidateTitles: Collection<String>, referenceTitles: Collection<String>): Boolean {
		val reference = referenceTitles.mapTo(hashSetOf(), ::normalize)
		return candidateTitles.any { normalize(it) in reference }
	}

	fun accepts(isExact: Boolean, bestScore: Float, secondScore: Float?): Boolean =
		isExact || bestScore >= MIN_SIMILARITY && (secondScore == null || bestScore - secondScore >= MIN_MARGIN)
}
