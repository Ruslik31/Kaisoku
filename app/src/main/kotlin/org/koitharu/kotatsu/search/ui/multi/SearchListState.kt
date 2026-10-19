package org.koitharu.kotatsu.search.ui.multi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState

internal fun observeSearchResults(
    results: Flow<List<SearchResultsListModel>>,
    isLoading: Flow<Boolean>,
    includeDisabledSources: Flow<Boolean>,
    hideEmpty: Flow<Boolean>,
): Flow<List<ListModel>> = combine(results, isLoading, includeDisabledSources, hideEmpty) {
        list, loading, includeDisabled, hideEmptyVal ->
    val filteredList = if (hideEmptyVal) list.filter { it.list.isNotEmpty() } else list
    when {
        filteredList.isEmpty() -> listOf(
            if (loading) LoadingState else EmptyState(
                icon = R.drawable.ic_empty_common,
                textPrimary = R.string.nothing_found,
                textSecondary = R.string.text_search_holder_secondary,
                actionStringRes = 0,
            ),
        )
        loading -> filteredList + LoadingFooter()
        includeDisabled -> filteredList
        else -> filteredList + ButtonFooter(R.string.search_disabled_sources)
    }
}
