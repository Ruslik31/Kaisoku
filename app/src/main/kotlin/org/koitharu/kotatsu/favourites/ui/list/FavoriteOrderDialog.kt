package org.koitharu.kotatsu.favourites.ui.list

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.parsers.model.Manga

/** An isolated edit session: only Apply publishes an order, never an intermediate drag. */
internal fun showFavoriteOrderDialog(context: Context, manga: List<Manga>, save: (List<Long>) -> Unit) {
    val adapter = FavoriteOrderAdapter(manga.distinctBy { it.id })
    val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        this.adapter = adapter
        minimumHeight = (context.resources.displayMetrics.heightPixels * .45f).toInt()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * .55f).toInt())
    }
    val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false
        override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean =
            adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    })
    helper.attachToRecyclerView(list)
    adapter.startDrag = helper::startDrag
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.favorites_reorder)
        .setMessage(R.string.favorites_reorder_unfiltered)
        .setView(list)
        .setPositiveButton(android.R.string.ok) { _, _ -> save(adapter.ids()) }
        .setNegativeButton(android.R.string.cancel, null)
        .create().apply {
            setOnDismissListener { helper.attachToRecyclerView(null); list.adapter = null }
        }.show()
}

private class FavoriteOrderAdapter(values: List<Manga>) : RecyclerView.Adapter<FavoriteOrderAdapter.Holder>() {
    private val items = values.toMutableList()
    var startDrag: (RecyclerView.ViewHolder) -> Unit = {}
    init { setHasStableIds(true) }
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id
    fun ids(): List<Long> = items.map { it.id }
    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent)
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun onViewRecycled(holder: Holder) { holder.cover.disposeImage(); super.onViewRecycled(holder) }

    @SuppressLint("ClickableViewAccessibility")
    inner class Holder(parent: ViewGroup) : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val padding = (8 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }) {
        val cover = CoverImageView(parent.context)
        private val title = TextView(parent.context)
        init {
            val row = itemView as LinearLayout
            val density = row.resources.displayMetrics.density
            row.addView(cover, LinearLayout.LayoutParams((48 * density).toInt(), (68 * density).toInt()))
            title.maxLines = 3
            title.setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val handle = ImageButton(parent.context).apply {
                setImageResource(R.drawable.ic_reorder_handle)
                contentDescription = context.getString(R.string.favorites_reorder)
                setBackgroundResource(android.R.color.transparent)
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        startDrag(this@Holder)
                    }
                    false
                }
            }
            row.addView(handle, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
            ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
                parent.context.getString(R.string.favorites_move_up)) { _, _ -> move(bindingAdapterPosition, bindingAdapterPosition - 1) }
            ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
                parent.context.getString(R.string.favorites_move_down)) { _, _ -> move(bindingAdapterPosition, bindingAdapterPosition + 1) }
        }
        fun bind(manga: Manga) {
            title.text = manga.title
            cover.setImageAsync(manga.coverUrl, manga)
        }
    }
}
