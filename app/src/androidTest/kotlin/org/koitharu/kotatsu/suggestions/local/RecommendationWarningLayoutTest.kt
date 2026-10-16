package org.koitharu.kotatsu.suggestions.local

import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.recommendationWarningAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.RecommendationWarning
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RecommendationWarningLayoutTest {
    @Test fun diagnosticsOnlyShowSmallAccessibleWarningIconsAtEveryFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (language in listOf("en", "ru", "ar")) for (scale in listOf(1f, 1.5f, 2f)) {
                val base = instrumentation.targetContext
                val configuration = Configuration(base.resources.configuration).apply {
                    fontScale = scale
                    setLocales(LocaleList(Locale(language)))
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_Kotatsu)
                val parent = RecyclerView(context).apply { layoutManager = GridLayoutManager(context, 3) }
                val adapter = BaseListAdapter<ListModel>().addDelegate(
                    ListItemType.RECOMMENDATION_WARNING,
                    recommendationWarningAD(),
                )
                val details = "Unable to resolve host\n" + "at <anonymous> (<lnreader-plugin>)\n".repeat(1000)
                adapter.items = listOf(RecommendationWarning(details))
                val holder = adapter.onCreateViewHolder(parent, ListItemType.RECOMMENDATION_WARNING.ordinal)
                adapter.onBindViewHolder(holder, 0)
                val row = holder.itemView
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.layoutParams.height)
                val density = context.resources.displayMetrics.density
                row.measure(
                    View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
                )
                assertEquals("$language / $scale warning height", (48 * density).toInt(), row.measuredHeight)
                val icon = row.findViewById<ImageButton>(R.id.button_details)
                assertEquals(context.getString(R.string.recommendation_sources_with_errors), icon.contentDescription)
                assertTrue(icon.hasOnClickListeners())
                assertEquals(48 * density, icon.measuredWidth.toFloat(), 1f)
            }
        }
    }
}
