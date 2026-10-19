package org.koitharu.kotatsu.favourites.ui.container

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.databinding.ItemFavoritesCategoryTileBinding
import org.koitharu.kotatsu.favourites.domain.CategoryOverview

internal class CategoryOverviewAdapter(private val onOpen: (Long) -> Unit) :
    ListAdapter<CategoryOverview, CategoryOverviewAdapter.Holder>(DIFF) {

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = getItem(position).id
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemFavoritesCategoryTileBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: Holder) {
        holder.binding.covers.disposeCovers()
        super.onViewRecycled(holder)
    }

    inner class Holder(val binding: ItemFavoritesCategoryTileBinding) : RecyclerView.ViewHolder(binding.root) {
        private var categoryId = 0L
        init { binding.root.setOnClickListener { onOpen(categoryId) } }
        fun bind(item: CategoryOverview) {
            categoryId = item.id
            binding.title.text = item.title ?: binding.root.context.getString(R.string.all_favourites)
            binding.count.text = binding.root.resources.getQuantityStringSafe(R.plurals.items, item.count, item.count)
            binding.covers.setCoversAsync(item.covers)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CategoryOverview>() {
            override fun areItemsTheSame(old: CategoryOverview, new: CategoryOverview) = old.id == new.id
            override fun areContentsTheSame(old: CategoryOverview, new: CategoryOverview) = old == new
        }
    }
}
