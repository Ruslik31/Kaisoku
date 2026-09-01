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
		assertTrue(
			canCommitReaderState(
				pages,
				pages,
				capturedRevision = 4,
				currentRevision = 4,
				capturedGeneration = 8,
				currentGeneration = 8,
			),
		)
	}

	@Test
	fun authoritativeSaveInvalidatesDelayedStateCommit() {
		val pages = listOf(1, 2, 3)
		assertFalse(
			canCommitReaderState(
				pages,
				pages,
				capturedRevision = 4,
				currentRevision = 5,
				capturedGeneration = 8,
				currentGeneration = 8,
			),
		)
	}

	@Test
	fun staleAdapterGenerationCannotAddressNewPageList() {
		val pages = listOf(1, 2, 3)
		assertFalse(
			canCommitReaderState(
				pages,
				pages,
				capturedRevision = 4,
				currentRevision = 4,
				capturedGeneration = 8,
				currentGeneration = 9,
			),
		)
	}

	@Test
	fun lifecycleSnapshotOnlyAppliesToItsOwnContentGeneration() {
		val state = ReaderState(chapterId = 50, page = 4, scroll = 800)
		val snapshot = ReaderStateSnapshot(state, contentGeneration = 12)

		assertTrue(snapshot.stateForGeneration(12) === state)
		assertTrue(snapshot.stateForGeneration(13) == null)
	}
}
