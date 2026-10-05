package org.koitharu.kotatsu.search.ui.multi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** A continuation waiting for the first stage does not own that stage's cancellation. */
internal suspend fun cancelSearchTasks(jobs: List<Job>) {
    jobs.forEach { it.cancel() }
    jobs.joinAll()
}

/** Results are published as each task finishes; remote work never waits for a local disk scan. */
internal suspend fun runSearchTasks(
    localTasks: List<suspend () -> Unit>,
    remoteTasks: suspend CoroutineScope.() -> Unit,
) = coroutineScope {
    localTasks.forEach { task -> launch { task() } }
    launch(block = remoteTasks)
    Unit
}
