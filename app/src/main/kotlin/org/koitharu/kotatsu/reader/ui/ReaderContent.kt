package org.koitharu.kotatsu.reader.ui

import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

data class ReaderContent(
	val pages: List<ReaderPage>,
	val state: ReaderState?,
	val replacementId: Long = 0,
	/**
	 * Monotonically increasing publication id. UI callbacks carry the generation of the list that
	 * their adapter has actually applied, so positions from an older AsyncListDiffer snapshot can
	 * never be resolved against a newer page list.
	 */
	val generation: Long = 0,
	/** Allow a retained reader to consume a replacement while resuming from the background. */
	val forceStateRestore: Boolean = false,
)

/**
 * Page counts are not a safe content revision: two chapters can have the same number of pages.
 * Reader page-change work must only commit against the exact list snapshot that it captured.
 */
internal fun isCurrentPageListSnapshot(captured: List<*>, current: List<*>): Boolean =
	captured === current

internal fun canCommitReaderState(
	capturedPages: List<*>,
	currentPages: List<*>,
	capturedRevision: Long,
	currentRevision: Long,
	capturedGeneration: Long,
	currentGeneration: Long,
): Boolean = capturedRevision == currentRevision &&
	capturedGeneration == currentGeneration &&
	isCurrentPageListSnapshot(capturedPages, currentPages)
