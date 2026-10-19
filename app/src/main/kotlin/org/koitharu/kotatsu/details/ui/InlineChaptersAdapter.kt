package org.koitharu.kotatsu.details.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView

/** Two fixed headers followed by the existing, independently diffed chapter adapter. */
internal object InlineChaptersAdapter {
    const val METADATA_TYPE = Int.MAX_VALUE - 10
    const val CONTROLS_TYPE = Int.MAX_VALUE - 9
    val headerTypes = setOf(METADATA_TYPE, CONTROLS_TYPE)

    fun create(metadata: View, controls: View, chapters: RecyclerView.Adapter<*>): ConcatAdapter = ConcatAdapter(
        ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build(),
        HeaderAdapter(metadata, METADATA_TYPE),
        HeaderAdapter(controls, CONTROLS_TYPE),
        chapters,
    )

    private class HeaderAdapter(private val view: View, private val type: Int) : RecyclerView.Adapter<HeaderHolder>() {
        override fun getItemCount() = 1
        override fun getItemViewType(position: Int) = type
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderHolder {
            check(view.parent == null) { "Details header is already attached" }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            return HeaderHolder(view)
        }
        override fun onBindViewHolder(holder: HeaderHolder, position: Int) = Unit
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)
}
