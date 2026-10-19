package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterBoundaryHapticGateTest {
    @Test fun firstChapterAndRepeatedPageScrollTranslationOrModeEventsAreSilent() {
        val gate = ChapterBoundaryHapticGate()
        assertFalse(gate.onPresented(first, focused = true))
        repeat(100) { assertFalse(gate.onPresented(first.copy(), focused = true)) }
    }

    @Test fun nextAndPreviousConfirmedTransitionsEachEmitExactlyOnce() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        assertTrue(gate.onPresented(second, focused = true))
        repeat(10) { assertFalse(gate.onPresented(second, focused = true)) }
        assertTrue(gate.onPresented(first, focused = true))
        assertFalse(gate.onPresented(first, focused = true))
    }

    @Test fun preloadsAndUnsettledRestorationCandidatesDoNotChangeThePresentedChapter() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        repeat(10) { assertFalse(gate.onPresented(null, focused = true)) }
        assertFalse(gate.onPresented(first, focused = true))
        assertTrue(gate.onPresented(second, focused = true))
    }

    @Test fun aDifferentTitleSeedsSilentlyEvenIfChapterIdsMatch() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        assertFalse(gate.onPresented(PresentedChapter(99, second.chapterId), focused = true))
        assertTrue(gate.onPresented(PresentedChapter(99, 3), focused = true))
    }

    @Test fun disablingAndReenablingTheEffectDoesNotReplayAChapterChange() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        gate.reset()
        assertFalse(gate.onPresented(second, focused = true))
        assertTrue(gate.onPresented(first, focused = true))
    }

    @Test fun unfocusedPresentationsAreSilentAndNeverQueueAnEffect() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        assertFalse(gate.onPresented(second, focused = false))
        assertFalse(gate.onPresented(second, focused = true))
        assertTrue(gate.onPresented(first, focused = true))
    }

    @Test fun chapterSheetSelectionCountsWhenTheNewChapterIsFirstPresentedAfterDismissal() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        assertFalse(gate.onPresented(null, focused = false))
        assertTrue(gate.onPresented(second, focused = true))
        assertFalse(gate.onPresented(second, focused = true))
    }

    @Test fun pauseBackgroundAndResumeDoNotReplayAChangedChapter() {
        val gate = ChapterBoundaryHapticGate()
        gate.onPresented(first, focused = true)
        gate.reset()
        assertFalse(gate.onPresented(null, focused = false))
        assertFalse(gate.onPresented(second, focused = true))
        assertFalse(gate.onPresented(second, focused = true))
        assertTrue(gate.onPresented(first, focused = true))
    }

    @Test fun recreationAndRepeatedRestorationAreSilent() {
        val old = ChapterBoundaryHapticGate()
        old.onPresented(first, focused = true)
        val recreated = ChapterBoundaryHapticGate()
        assertFalse(recreated.onPresented(null, focused = true))
        repeat(3) { assertFalse(recreated.onPresented(first, focused = true)) }
        assertTrue(recreated.onPresented(second, focused = true))
    }

    private val first = PresentedChapter(7, 1)
    private val second = PresentedChapter(7, 2)
}
