package org.koitharu.kotatsu.reader.ui.pager.vertical

import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.reader.ui.pager.BasePagerReaderFragment
import org.koitharu.kotatsu.reader.ui.config.ReaderZoomScope

@AndroidEntryPoint
class VerticalReaderFragment : BasePagerReaderFragment() {
    override val readerZoomScope = ReaderZoomScope.VERTICAL

    override fun onInitPager(pager: ViewPager2) {
        super.onInitPager(pager)
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
    }

    override fun onCreateAdvancedTransformer(): ViewPager2.PageTransformer = VerticalPageAnimTransformer()
}
