package org.koitharu.kotatsu.reader.domain

/**
 * Tracks which chapter prev/next button navigation is anchored to, so a burst of rapid presses
 * chains deterministically (N -> N+1 -> N+2) instead of each press re-deriving its base from the
 * live reading state.
 *
 * The live reading state is updated asynchronously by scrolling and by the re-anchor that follows
 * an adjacent-chapter preload; mid load it can momentarily report a neighbouring chapter, and a
 * press that is cancelled before it commits never updates it at all. Using it as the navigation
 * base is what made rapid presses fail to advance, jump to the start of the current chapter, or
 * move to the previous chapter instead of the next one.
 *
 * The anchor is only updated at safe points (a chapter is opened, a switch is issued, or scrolling
 * has settled with no load in flight), so it is immune to that transient noise.
 */
class ChapterSwitchCursor {

	@Volatile
	private var anchorChapterId: Long? = null

	/** Re-anchor to a known-current chapter (chapter opened, switch issued, or scrolling settled). */
	fun settle(chapterId: Long?) {
		anchorChapterId = chapterId
	}

	/**
	 * Resolve the target chapter id for a relative move, chaining from the current anchor and
	 * advancing the anchor to the result. Returns `null` (and leaves the anchor untouched) when the
	 * base chapter is unknown or the move runs past the first/last chapter.
	 *
	 * @param liveChapterId fallback base used when there is no anchor yet, or when the anchor points
	 * at a chapter that is no longer part of [allChapterIds] (e.g. after a branch change).
	 */
	fun resolveRelative(allChapterIds: List<Long>, liveChapterId: Long?, delta: Int): Long? {
		val anchorIndex = anchorChapterId?.let { id -> allChapterIds.indexOf(id) } ?: -1
		val baseIndex = if (anchorIndex >= 0) {
			anchorIndex
		} else {
			liveChapterId?.let { id -> allChapterIds.indexOf(id) } ?: -1
		}
		if (baseIndex < 0) {
			return null
		}
		val target = allChapterIds.getOrNull(baseIndex + delta) ?: return null
		anchorChapterId = target
		return target
	}
}
