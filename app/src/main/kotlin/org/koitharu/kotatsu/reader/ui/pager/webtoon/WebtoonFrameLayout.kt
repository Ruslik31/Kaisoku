package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.R

class WebtoonFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var _target: WebtoonImageView? = null
    val target: WebtoonImageView
        get() = _target ?: findViewById<WebtoonImageView?>(R.id.ssiv).also {
            _target = it
        }

    val isAnimationReady: Boolean
        get() = !isLayoutRequested && !target.isVisible && findViewById<CoilImageView>(R.id.animatedView).let {
            it.isVisible && it.drawable != null && it.isLaidOut && !it.isLayoutRequested && it.height > 0
        }

    fun dispatchVerticalScroll(dy: Int): Int {
        if (dy == 0 || !target.isVisible) {
            return 0
        }
        val oldScroll = target.getScroll()
        target.scrollBy(dy)
        return target.getScroll() - oldScroll
    }
}
