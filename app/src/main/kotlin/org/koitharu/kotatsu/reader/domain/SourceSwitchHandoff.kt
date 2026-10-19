package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/** Finish old reader work and its serialized saves before moving the title's history. */
internal suspend fun <T> sourceSwitchHandoff(
    readerJobs: List<Job?>,
    saveState: suspend () -> Unit,
    migrate: suspend () -> T,
): T {
    val pending = readerJobs.filterNotNull().distinct()
    pending.forEach { it.cancel() }
    pending.joinAll()
    saveState()
    return migrate()
}
