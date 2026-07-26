package org.koitharu.kotatsu.explore.ui.model

import org.koitharu.kotatsu.customsource.domain.CustomSource
import org.koitharu.kotatsu.list.ui.model.ListModel

data class BrowserSourceItem(
	val source: CustomSource,
) : ListModel {

	val id: Long
		get() = source.id

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is BrowserSourceItem && other.source.id == source.id
	}
}
