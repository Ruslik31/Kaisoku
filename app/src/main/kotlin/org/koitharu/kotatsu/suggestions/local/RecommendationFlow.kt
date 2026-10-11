package org.koitharu.kotatsu.suggestions.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Keep cached cards, but stop profile queries and discovery as soon as the screen stops observing. */
internal fun <T> Flow<T>.shareRecommendations(scope: CoroutineScope, initialValue: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), initialValue)
