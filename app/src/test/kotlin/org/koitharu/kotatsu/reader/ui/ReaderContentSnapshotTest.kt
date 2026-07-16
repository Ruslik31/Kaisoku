package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentSnapshotTest {

	@Test
	fun exactPageListSnapshotRemainsCurrent() {
		val pages = listOf(1, 2, 3)
		assertTrue(isCurrentPageListSnapshot(pages, pages))
	}

	@Test
	fun equalSizedReplacementIsNotTheCurrentSnapshot() {
		val oldChapterPages = listOf(1, 2, 3)
		val newChapterPages = listOf(4, 5, 6)
		assertFalse(isCurrentPageListSnapshot(oldChapterPages, newChapterPages))
	}

	@Test
	fun valueEqualReEmissionIsStillANewSnapshot() {
		val firstEmission = listOf(1, 2, 3)
		val secondEmission = firstEmission.toList()
		assertFalse(isCurrentPageListSnapshot(firstEmission, secondEmission))
	}

	@Test
	fun matchingSnapshotAndRevisionCanCommitState() {
		val pages = listOf(1, 2, 3)
		assertTrue(canCommitReaderState(pages, pages, capturedRevision = 4, currentRevision = 4))
	}

	@Test
	fun authoritativeSaveInvalidatesDelayedStateCommit() {
		val pages = listOf(1, 2, 3)
		assertFalse(canCommitReaderState(pages, pages, capturedRevision = 4, currentRevision = 5))
	}
}
