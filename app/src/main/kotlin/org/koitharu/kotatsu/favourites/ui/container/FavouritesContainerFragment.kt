package org.koitharu.kotatsu.favourites.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.GridLayoutManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.doOnPageChanged
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentFavouritesContainerBinding
import org.koitharu.kotatsu.databinding.ItemEmptyStateBinding
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment

@AndroidEntryPoint
class FavouritesContainerFragment : BaseFragment<FragmentFavouritesContainerBinding>(),
    ActionModeListener,
    RecyclerViewOwner,
    ViewStub.OnInflateListener,
    View.OnClickListener {

    private val viewModel: FavouritesContainerViewModel by viewModels()
    private var showingOverview = true
    private var restoringCategories = false
    private var overviewAdapter: CategoryOverviewAdapter? = null
    private val overviewBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showingOverview = true; updatePresentation() }
    }

    val isCategoryOverviewVisible: Boolean
        get() = viewModel.overviewEnabled.value && showingOverview


    override val recyclerView: RecyclerView?
        get() = if (isCategoryOverviewVisible) viewBinding?.overview else (findCurrentFragment() as? RecyclerViewOwner)?.recyclerView

    val categoryId: Long? get() = if (isCategoryOverviewVisible) null else (findCurrentFragment() as? FavouritesListFragment)?.categoryId

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ) = FragmentFavouritesContainerBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: FragmentFavouritesContainerBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        showingOverview = savedInstanceState?.getBoolean("category_overview", true) ?: true
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, overviewBack)
        val gridAdapter = CategoryOverviewAdapter(::openCategory)
        overviewAdapter = gridAdapter
        binding.overview.layoutManager = GridLayoutManager(requireContext(),
            if (resources.configuration.screenWidthDp >= 600) 3 else 2)
        binding.overview.adapter = gridAdapter
        viewModel.categoryCards.observe(viewLifecycleOwner) { gridAdapter.submitList(it) }
        viewModel.overviewEnabled.observe(viewLifecycleOwner) { enabled ->
            if (enabled && savedInstanceState == null) showingOverview = true
            updatePresentation()
        }
        val pagerAdapter = FavouritesContainerAdapter(this)
        binding.pager.adapter = pagerAdapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.recyclerView?.isNestedScrollingEnabled = false
        TabLayoutMediator(
            binding.tabs,
            binding.pager,
            FavouritesTabConfigurationStrategy(pagerAdapter, viewModel, router),
        ).attach()
        binding.pager.doOnPageChanged { position ->
            if (!restoringCategories) pagerAdapter.getItemOrNull(position)?.let {
                viewModel.onCategorySelected(it.id)
            }
        }
        binding.stubEmpty.setOnInflateListener(this)
        actionModeDelegate.addListener(this)
        viewModel.categories.observe(viewLifecycleOwner, object : FlowCollector<List<FavouriteTabModel>> {
            override suspend fun emit(value: List<FavouriteTabModel>) {
                val previous = pagerAdapter.getItemOrNull(binding.pager.currentItem)?.id
                if (previous != null && value.none { it.id == previous }) showingOverview = true
                restoringCategories = true
                pagerAdapter.submitList(value, Runnable {
                    restoringCategories = false
                    if (viewBinding !== binding || value.isEmpty()) {
                        return@Runnable
                    }
                    val position = viewModel.getCategoryPosition(value)
                    if (binding.pager.currentItem != position) {
                        binding.pager.setCurrentItem(position, false)
                    }
                    updatePresentation()
                    value.getOrNull(binding.pager.currentItem)?.let {
                        viewModel.onCategorySelected(it.id)
                    }
                })
            }
        })
        viewModel.isEmpty.observe(viewLifecycleOwner, ::onEmptyStateChanged)
        viewModel.showCategoryStrip.observe(viewLifecycleOwner) { visible ->
            updatePresentation()
        }
        addMenuProvider(FavouritesContainerMenuProvider(
            router,
            { !viewModel.showCategoryStrip.value && viewModel.categories.value.isNotEmpty() },
            ::chooseCategory,
            { viewModel.overviewEnabled.value && !showingOverview },
            { showingOverview = true; updatePresentation() },
        ))
        viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
    }

    override fun onDestroyView() {
        actionModeDelegate.removeListener(this)
        viewBinding?.overview?.adapter = null
        overviewAdapter = null
        super.onDestroyView()
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    override fun onActionModeStarted(mode: ActionMode) {
        overviewBack.isEnabled = false
        viewBinding?.run {
            pager.isUserInputEnabled = false
            tabs.setTabsEnabled(false)
        }
    }

    override fun onActionModeFinished(mode: ActionMode) {
        viewBinding?.run {
            pager.isUserInputEnabled = true
            tabs.setTabsEnabled(true)
        }
        updatePresentation()
    }

    override fun onInflate(stub: ViewStub?, inflated: View) {
        val stubBinding = ItemEmptyStateBinding.bind(inflated)
        stubBinding.icon.setImageAsync(R.drawable.ic_empty_favourites)
        stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
        stubBinding.textSecondary.setTextAndVisible(R.string.empty_favourite_categories)
        stubBinding.buttonRetry.setTextAndVisible(R.string.manage)
        stubBinding.buttonRetry.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_retry -> router.openFavoriteCategories()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("category_overview", showingOverview)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); updatePresentation() }
    override fun onPause() { overviewBack.isEnabled = false; super.onPause() }

    private fun onEmptyStateChanged(isEmpty: Boolean) = updatePresentation()

    private fun updatePresentation() {
        val binding = viewBinding ?: return
        val empty = viewModel.isEmpty.value
        val overview = isCategoryOverviewVisible && !empty
        binding.overview.isVisible = overview
        binding.pager.isVisible = !empty && !overview
        binding.tabs.isVisible = !empty && !overview && viewModel.showCategoryStrip.value
        binding.stubEmpty.isVisible = empty
        overviewBack.isEnabled = isResumed && viewModel.overviewEnabled.value && !showingOverview
        requireActivity().invalidateOptionsMenu()
    }

    fun openCategory(id: Long) {
        val binding = viewBinding ?: return
        val index = viewModel.categories.value.indexOfFirst { it.id == id }
        if (index < 0) return
        showingOverview = false
        viewModel.onCategorySelected(id)
        binding.pager.setCurrentItem(index, false)
        updatePresentation()
    }

    private fun chooseCategory() {
        val binding = viewBinding ?: return
        val categories = viewModel.categories.value
        if (categories.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.choose_favorites_category)
            .setSingleChoiceItems(
                categories.map { it.title ?: getString(R.string.all_favourites) }.toTypedArray(),
                binding.pager.currentItem,
            ) { dialog, position ->
                val category = categories[position]
                val current = viewModel.categories.value.indexOfFirst { it.id == category.id }
                if (current >= 0 && viewBinding === binding) openCategory(category.id)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun findCurrentFragment(): Fragment? {
        return childFragmentManager.findCurrentPagerFragment(
            viewBinding?.pager ?: return null,
        )
    }
}
