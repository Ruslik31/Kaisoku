package org.koitharu.kotatsu.favourites.domain

import org.koitharu.kotatsu.favourites.domain.model.Cover

data class CategoryOverview(
    val id: Long,
    val title: String?,
    val count: Int,
    val covers: List<Cover>,
)
