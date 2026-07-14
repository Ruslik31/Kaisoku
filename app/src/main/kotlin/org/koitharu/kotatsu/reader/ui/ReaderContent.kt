package org.koitharu.kotatsu.reader.ui

import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

data class ReaderContent(
	val pages: List<ReaderPage>,
	val state: ReaderState?
)

/**
 * Page counts are not a safe content revision: two chapters can have the same number of pages.
 * Reader page-change work must only commit against the exact list snapshot that it captured.
 */
internal fun isCurrentPageListSnapshot(captured: List<*>, current: List<*>): Boolean =
	captured === current
