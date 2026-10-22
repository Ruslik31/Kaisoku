package org.koitharu.kotatsu.reader.ui.pager.standard

import android.annotation.SuppressLint
import org.koitharu.kotatsu.reader.ui.pager.displaySourceWidth
import org.koitharu.kotatsu.reader.ui.pager.displaySourceHeight
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.DefaultOnStateChangedListener
import com.davemorrissey.labs.subscaleview.DefaultOnAnimationEventListener
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.config.ReaderZoomScope
import org.koitharu.kotatsu.reader.ui.config.relativeReaderZoom
import org.koitharu.kotatsu.reader.ui.config.rememberedPageScale
import org.koitharu.kotatsu.reader.ui.config.isDoubleTapZoomComplete
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

open class PageHolder(
    owner: LifecycleOwner,
    binding: ItemPageBinding,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
    private val zoomScope: ReaderZoomScope = ReaderZoomScope.STANDARD,
) : BasePageHolder<ItemPageBinding>(
    binding = binding,
    loader = loader,
    readerSettingsProducer = readerSettingsProducer,
    networkState = networkState,
    exceptionResolver = exceptionResolver,
    lifecycleOwner = owner,
), ZoomControl.ZoomControlListener, OnApplyWindowInsetsListener {

    override val ssiv = binding.ssiv
    private val zoomMemory = readerSettingsProducer.zoomMemory
    private var touchZoom: Float? = null
    private var zoomBinding: ReaderPage? = null
    private var hasPresentedPage = false
    private val zoomListener = object : DefaultOnStateChangedListener {
        override fun onScaleChanged(newScale: Float, origin: Int) {
            if (!isResumed() || isAnimatedPageDisplayed || !zoomMemory.isEnabled) return
            val ratio = relativeReaderZoom(newScale, fittedScale()) ?: return
            when (origin) {
                SubsamplingScaleImageView.ORIGIN_TOUCH -> touchZoom = ratio
                SubsamplingScaleImageView.ORIGIN_DOUBLE_TAP_ZOOM -> {
                    if (isDoubleTapZoomComplete(newScale, ssiv.minScale, ssiv.maxScale, ssiv.doubleTapZoomScale)) {
                        zoomMemory.record(zoomScope, ratio)
                    }
                }
            }
        }
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
    }

    override fun onApplyWindowInsets(
        v: View,
        insets: WindowInsetsCompat
    ): WindowInsetsCompat {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets.toWindowInsets()?.let {
                applyRoundedCorners(it)
            }
        }
        return insets
    }

    override fun onConfigChanged(settings: ReaderSettings) {
        touchZoom = null
        super.onConfigChanged(settings)
        binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
    }

    override fun onPagePresentationChanging() {
        touchZoom = null
        super.onPagePresentationChanging()
    }

    override fun onDestroy() {
        touchZoom = null
        ssiv.onStateChangedListener = null
        super.onDestroy()
    }

    @SuppressLint("SetTextI18n")
    override fun onBind(data: ReaderPage) {
        super.onBind(data)
        if (zoomBinding?.id != data.id || zoomBinding?.url != data.url) hasPresentedPage = false
        zoomBinding = data
        touchZoom = null
        ssiv.onStateChangedListener = zoomListener
        binding.textViewNumber.text = (data.index + 1).toString()
    }

    override fun onPageReady() {
        preparePageZoom()
        binding.ssiv.maxScale = defaultMaxScale(binding.ssiv)
        binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
        when (settings.zoomMode) {
            ZoomMode.FIT_CENTER -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                binding.ssiv.resetScaleAndCenter()
            }

            ZoomMode.FIT_HEIGHT -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                binding.ssiv.minScale = binding.ssiv.height / binding.ssiv.displaySourceHeight.toFloat()
                binding.ssiv.setScaleAndCenter(
                    binding.ssiv.minScale,
                    PointF(0f, binding.ssiv.displaySourceHeight / 2f),
                )
            }

            ZoomMode.FIT_WIDTH -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                binding.ssiv.minScale = binding.ssiv.width / binding.ssiv.displaySourceWidth.toFloat()
                binding.ssiv.setScaleAndCenter(
                    binding.ssiv.minScale,
                    PointF(binding.ssiv.displaySourceWidth / 2f, 0f),
                )
            }

            ZoomMode.KEEP_START -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                binding.ssiv.setScaleAndCenter(
                    binding.ssiv.maxScale,
                    PointF(0f, 0f),
                )
            }
        }
    }

    protected fun preparePageZoom() {
        touchZoom = null
        if (isResumed()) hasPresentedPage = true
    }

    override fun restoreNewPageZoom() {
        val scale = rememberedPageScale(zoomMemory.get(zoomScope), fittedScale(), ssiv.minScale, ssiv.maxScale) ?: return
        val startX = if (zoomScope == ReaderZoomScope.REVERSED) ssiv.displaySourceWidth.toFloat() else 0f
        val center = when (settings.zoomMode) {
            ZoomMode.FIT_CENTER -> PointF(ssiv.displaySourceWidth / 2f, ssiv.displaySourceHeight / 2f)
            ZoomMode.FIT_HEIGHT -> PointF(startX, ssiv.displaySourceHeight / 2f)
            ZoomMode.FIT_WIDTH -> PointF(ssiv.displaySourceWidth / 2f, 0f)
            ZoomMode.KEEP_START -> PointF(startX, 0f)
        }
        ssiv.setScaleAndCenter(scale, center)
    }

    override fun onResume() {
        super.onResume()
        // A prefetched page can have fitted before the previous page's final pinch.
        if (!hasPresentedPage && ssiv.isReady && !isAnimatedPageDisplayed) restoreNewPageZoom()
        hasPresentedPage = true
    }

    override fun onPause() {
        touchZoom = null
        super.onPause()
    }

    override fun onRecycled() {
        touchZoom = null
        zoomBinding = null
        hasPresentedPage = false
        ssiv.onStateChangedListener = null
        super.onRecycled()
    }

    override fun onPageTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> touchZoom = null
            MotionEvent.ACTION_UP -> {
                val ratio = touchZoom
                touchZoom = null
                if (ratio != null && isResumed() && !isAnimatedPageDisplayed) zoomMemory.record(zoomScope, ratio)
            }
        }
    }

    private fun fittedScale(): Float = minOf(
        ssiv.width.toFloat() / ssiv.displaySourceWidth,
        ssiv.height.toFloat() / ssiv.displaySourceHeight,
    )

    override fun onZoomIn() {
        scaleBy(1.2f)
    }

    override fun onZoomOut() {
        scaleBy(0.8f)
    }

    @SuppressLint("RtlHardcoded")
    @RequiresApi(Build.VERSION_CODES.S)
    protected open fun applyRoundedCorners(insets: WindowInsets) {
        binding.textViewNumber.updateLayoutParams<FrameLayout.LayoutParams> {
            val baseMargin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
            val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
            val corner = when {
                absoluteGravity and Gravity.LEFT == Gravity.LEFT -> {
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                }

                absoluteGravity and Gravity.RIGHT == Gravity.RIGHT -> {
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                }

                else -> {
                    null
                }
            }
            setMargins(baseMargin + (corner?.radius ?: 0))
        }
    }

    private fun scaleBy(factor: Float) {
        val ssiv = binding.ssiv
        val page = boundData ?: return
        val center = ssiv.getCenter() ?: return
        val newScale = ssiv.scale * factor
        ssiv.animateScaleAndCenter(newScale, center)?.apply {
            withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
            withInterpolator(DecelerateInterpolator())
            withOnAnimationEventListener(object : DefaultOnAnimationEventListener {
                override fun onComplete() {
                    if (boundData?.id == page.id && boundData?.url == page.url && isResumed() && !isAnimatedPageDisplayed) {
                        relativeReaderZoom(ssiv.scale, fittedScale())?.let { zoomMemory.record(zoomScope, it) }
                    }
                }
            })
            start()
        }
    }
}
