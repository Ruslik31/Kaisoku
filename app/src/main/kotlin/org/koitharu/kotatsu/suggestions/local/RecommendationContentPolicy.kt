package org.koitharu.kotatsu.suggestions.local

import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import java.text.Normalizer
import java.util.Locale

/** Local evidence only: list responses from mixed-content sources often omit their content rating. */
internal object RecommendationContentPolicy {
    private val adultTags = setOf(
        "adult", "adult only", "adult (18+)", "18+", "r18", "r-18", "nsfw", "hentai", "smut",
        "erotica", "porn", "pornographic", "pornography", "sexual content", "explicit sexual content",
        "хентай", "хёнтай", "эротика", "порно", "порнография", "для взрослых", "成人", "成人向け", "色情",
    )
    private val whitespace = Regex("[\\s_]+")

    fun isNsfw(manga: Manga): Boolean = manga.isNsfw() || hasAdultMetadata(manga)

    fun allowed(manga: Manga, excludeNsfw: Boolean): Boolean = !excludeNsfw || !isNsfw(manga)

    private fun hasAdultMetadata(manga: Manga): Boolean = manga.contentRating == ContentRating.ADULT ||
        manga.tags.any { tag -> normalize(tag.title) in adultTags || normalize(tag.key) in adultTags }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(whitespace, " ")

    fun withKnownRating(manga: Manga, known: Manga?, override: ContentRating? = null): Manga {
        val sameTitle = known?.takeIf { it.id == manga.id && it.source.name == manga.source.name }
        val rating = override ?: when {
            hasAdultMetadata(manga) || (sameTitle != null && hasAdultMetadata(sameTitle)) -> ContentRating.ADULT
            else -> manga.contentRating ?: sameTitle?.contentRating
        }
        return if (rating == manga.contentRating) manga else manga.copy(contentRating = rating)
    }

    /** A preferred readable/related copy must not erase an adult rating on its trusted duplicate. */
    fun retainAdultMetadata(values: List<Recommendation>): List<Recommendation> {
        val adult = BooleanArray(values.size) { hasAdultMetadata(values[it].manga) }
        if (adult.none { it }) return values
        val identities = values.map(RecommendationIdentity::feedbackKeys)
        val copies = mutableMapOf<String, MutableList<Int>>()
        identities.forEachIndexed { index, keys ->
            keys.forEach { copies.getOrPut(it, ::mutableListOf).add(index) }
        }
        val pending = ArrayDeque<Int>()
        adult.indices.filterTo(pending) { adult[it] }
        while (pending.isNotEmpty()) {
            identities[pending.removeFirst()].forEach { key ->
                copies.remove(key).orEmpty().forEach { index ->
                    if (!adult[index]) { adult[index] = true; pending.addLast(index) }
                }
            }
        }
        return values.mapIndexed { index, value ->
            if (value.manga.contentRating == ContentRating.ADULT || !adult[index]) value
            else value.copy(target = when (val target = value.target) {
                is RecommendationTarget.SourceTitle -> target.copy(manga = target.manga.copy(contentRating = ContentRating.ADULT))
                is RecommendationTarget.PublicTitle -> target.copy(manga = target.manga.copy(contentRating = ContentRating.ADULT))
            })
        }
    }
}
