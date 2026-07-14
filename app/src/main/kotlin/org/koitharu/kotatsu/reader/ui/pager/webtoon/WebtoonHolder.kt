package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageWebtoonBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder

class WebtoonHolder internal constructor(
	owner: LifecycleOwner,
	binding: ItemPageWebtoonBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	private val pageSizeCache: WebtoonPageSizeCache,
) : BasePageHolder<ItemPageWebtoonBinding>(
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
) {

	override val ssiv = binding.ssiv

	private var scrollToRestore = 0
	private var scrollPercentToRestore = -1 // percentage * 10000, or -1 if none
	private var isInitialScrollApplied = false
	private var boundPageKey: WebtoonPageKey? = null
	// Guards a deferred percent restore: if the user scrolls before the image becomes ready, this
	// returns false and the (now stale) restore is dropped instead of teleporting the view back.
	private var restoreValidator: (() -> Boolean)? = null

	init {
		bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
	}

	override fun onBind(data: org.koitharu.kotatsu.reader.ui.pager.ReaderPage) {
		super.onBind(data)
		val newPageKey = WebtoonPageKey(data.chapterId, data.id)
		if (boundPageKey != null && boundPageKey != newPageKey) {
			// A cached holder can be rebound without entering the recycled pool first. Do not leave
			// the previous panel visible below the new page's loading UI.
			binding.ssiv.recycle()
		}
		boundPageKey = newPageKey
		binding.ssiv.setPlaceholderSize(pageSizeCache[newPageKey])
		scrollPercentToRestore = -1
		scrollToRestore = 0
		isInitialScrollApplied = false
		restoreValidator = null
	}

	override fun onReady() {
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		boundPageKey?.let { key ->
			pageSizeCache.put(key, binding.ssiv.sWidth, binding.ssiv.sHeight)
		}
		when {
			scrollPercentToRestore >= 0 -> {
				val percent = scrollPercentToRestore
				val validator = restoreValidator
				scrollPercentToRestore = -1
				scrollToRestore = 0
				restoreValidator = null
				isInitialScrollApplied = true
				if (validator == null || validator()) {
					binding.ssiv.post {
						if (validator == null || validator()) {
							applyScrollPercent(percent)
						}
					}
				}
			}

			scrollToRestore != 0 -> {
				val scroll = scrollToRestore
				scrollToRestore = 0
				isInitialScrollApplied = true
				binding.ssiv.post {
					binding.ssiv.scrollTo(scroll)
				}
			}

			!isInitialScrollApplied -> {
				val recyclerView = itemView.parent as? WebtoonRecyclerView
				val pageKey = boundPageKey
				val scrollGeneration = recyclerView?.scrollGeneration
				val wasAboveViewport = itemView.bottom <= 0
				val wasBelowViewport = recyclerView != null && itemView.top >= recyclerView.height
				val percent = calculateUnloadedPageScrollPercent(itemView.top, itemView.height)
				isInitialScrollApplied = true
				binding.ssiv.post {
					if (boundPageKey != pageKey) return@post
					when {
						wasAboveViewport -> binding.ssiv.scrollTo(binding.ssiv.getScrollRange())
						wasBelowViewport -> binding.ssiv.scrollTo(0)
						percent == 0 -> binding.ssiv.scrollTo(0)
						recyclerView != null && recyclerView.scrollGeneration != scrollGeneration -> {
							// The user kept moving during the ready/layout frame. A stale re-anchor is
							// worse than leaving the newly decoded image at its current position.
							binding.ssiv.scrollTo(binding.ssiv.getScroll())
						}
						else -> applyScrollPercent(percent)
					}
				}
			}
			else -> {
			}
		}
	}

	fun getScrollY() = binding.ssiv.getScroll()

	fun restoreScroll(scroll: Int) {
		if (binding.ssiv.isReady) {
			binding.ssiv.scrollTo(scroll)
		} else {
			scrollToRestore = scroll
		}
	}

	/**
	 * Combined offset: total content pixels above viewport top.
	 * = SSIV internal scroll + how far the item has scrolled above the RV top.
	 */
	fun getFullScrollOffset(): Int {
		val rvScrollAbove = (-itemView.top).coerceAtLeast(0)
		return binding.ssiv.getScroll() + rvScrollAbove
	}

	/**
	 * Scroll progress as fraction 0.0-1.0 of total page content height.
	 */
	fun getScrollProgress(): Float {
		val scrollRange = binding.ssiv.getScrollRange()
		val totalHeight = scrollRange + itemView.height
		if (totalHeight <= 0) return 0f
		return (getFullScrollOffset().toFloat() / totalHeight).coerceIn(0f, 1f)
	}

	/**
	 * Restore scroll from percentage (encoded as percentage * 10000).
	 * If SSIV is ready, applies immediately. Otherwise defers to onReady()
	 * when scrollRange is known and layout has settled.
	 */
	fun restoreScrollPercent(percentTimes10000: Int, isValid: () -> Boolean = { true }) {
		val normalized = percentTimes10000.coerceAtLeast(0)
		if (binding.ssiv.isReady) {
			// Applied synchronously while still current, so no staleness check is needed.
			applyScrollPercent(normalized)
			scrollPercentToRestore = -1
			restoreValidator = null
		} else {
			scrollPercentToRestore = normalized
			restoreValidator = isValid
		}
	}

	private fun applyScrollPercent(percentTimes10000: Int) {
		val fraction = percentTimes10000 / 10000f
		val scrollRange = binding.ssiv.getScrollRange()
		val totalHeight = scrollRange + itemView.height
		val targetOffset = (fraction * totalHeight).toInt()

		val ssivScroll = targetOffset.coerceAtMost(scrollRange)
		val rvOffset = ssivScroll - targetOffset

		val adapterPosition = bindingAdapterPosition
		if (adapterPosition != RecyclerView.NO_POSITION) {
			val layoutManager = (itemView.parent as? RecyclerView)?.layoutManager as? LinearLayoutManager
			layoutManager?.scrollToPositionWithOffset(adapterPosition, rvOffset)
		}
		binding.ssiv.scrollTo(ssivScroll)
		isInitialScrollApplied = true
	}
}
