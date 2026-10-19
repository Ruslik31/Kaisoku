package org.koitharu.kotatsu.reader.ui.pager.reversed

import org.koitharu.kotatsu.reader.ui.pager.displaySourceWidth
import org.koitharu.kotatsu.reader.ui.pager.displaySourceHeight
import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.config.ReaderZoomScope
import org.koitharu.kotatsu.reader.ui.pager.standard.PageHolder

class ReversedPageHolder(
    owner: LifecycleOwner,
    binding: ItemPageBinding,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
) : PageHolder(
    owner = owner,
    binding = binding,
    loader = loader,
    readerSettingsProducer = readerSettingsProducer,
    networkState = networkState,
    exceptionResolver = exceptionResolver,
    zoomScope = ReaderZoomScope.REVERSED,
) {

    init {
        (binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
            .gravity = Gravity.START or Gravity.BOTTOM
    }

    override fun onPageReady() {
        preparePageZoom()
        with(binding.ssiv) {
            maxScale = defaultMaxScale(this)
            binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
            when (settings.zoomMode) {
                ZoomMode.FIT_CENTER -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                    resetScaleAndCenter()
                }

                ZoomMode.FIT_HEIGHT -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                    minScale = height / displaySourceHeight.toFloat()
                    setScaleAndCenter(
                        minScale,
                        PointF(displaySourceWidth.toFloat(), displaySourceHeight / 2f),
                    )
                }

                ZoomMode.FIT_WIDTH -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                    minScale = width / displaySourceWidth.toFloat()
                    setScaleAndCenter(
                        minScale,
                        PointF(displaySourceWidth / 2f, 0f),
                    )
                }

                ZoomMode.KEEP_START -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                    setScaleAndCenter(
                        maxScale,
                        PointF(displaySourceWidth.toFloat(), 0f),
                    )
                }
            }
        }
    }
}
