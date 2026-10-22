package org.koitharu.kotatsu.favourites.domain

import androidx.annotation.Keep
import java.text.Collator
import java.util.Locale

@Keep
enum class CategorySortOrder { MANUAL, NAME, CREATED, COUNT }

internal data class CategorySortKey(
    val id: Long,
    val title: String,
    val manual: Int,
    val createdAt: Long,
    val count: Int,
)

internal fun sortFavoriteCategories(
    items: List<CategorySortKey>,
    order: CategorySortOrder,
    ascending: Boolean,
    locale: Locale = Locale.getDefault(),
): List<CategorySortKey> {
    val names = Collator.getInstance(locale)
    return items.sortedWith { left, right ->
        val primary = when (order) {
            CategorySortOrder.MANUAL -> left.manual.compareTo(right.manual)
            CategorySortOrder.NAME -> names.compare(left.title, right.title)
            CategorySortOrder.CREATED -> left.createdAt.compareTo(right.createdAt)
            CategorySortOrder.COUNT -> left.count.compareTo(right.count)
        }
        val directed = if (ascending || order == CategorySortOrder.MANUAL) primary else -primary.sign()
        if (directed != 0) directed else compareValuesBy(left, right, CategorySortKey::manual, CategorySortKey::id)
    }
}

private fun Int.sign(): Int = when { this < 0 -> -1; this > 0 -> 1; else -> 0 }
