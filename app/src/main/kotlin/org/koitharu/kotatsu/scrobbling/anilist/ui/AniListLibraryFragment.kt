package org.koitharu.kotatsu.scrobbling.anilist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.activity.result.contract.ActivityResultContracts
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException
import com.google.android.material.tabs.TabLayout
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentAnilistLibraryBinding
import org.koitharu.kotatsu.databinding.ItemAnilistLibraryEntryBinding
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

@AndroidEntryPoint
class AniListLibraryFragment : BaseFragment<FragmentAnilistLibraryBinding>() {

    private val viewModel by viewModels<AniListLibraryViewModel>()
    private val listAdapter = AniListLibraryAdapter(::onEntryClicked)
    private var pendingEntry: TrackerLibraryEntry? = null
    private var pendingAccountId: Long? = null
    private var pendingRevision: Long = -1
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val entry = pendingEntry
        val manga = result.data?.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga
        pendingEntry = null
        if (result.resultCode == android.app.Activity.RESULT_OK && entry != null && manga != null) {
            val storage = viewModel.repository.scrobbler(entry.service).trackerRepository.storage
            if (storage.user?.id == pendingAccountId && storage.sessionVersion == pendingRevision) {
                router.showScrobblingSelectorSheet(manga, entry.service, entry.mediaId)
            }
        }
    }
    private val statusTabs = ArrayList<Pair<TabLayout.Tab, Pair<String?, Int>>>()

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentAnilistLibraryBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: FragmentAnilistLibraryBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.textBeta.setOnClickListener {
            org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog(requireContext()) {
                setTitle(R.string.tracker_library_beta)
                setMessage(R.string.tracker_beta_risks)
                setPositiveButton(android.R.string.ok, null)
            }.show()
        }
        pendingAccountId = savedInstanceState?.getLong("pending_tracker_account")
        pendingRevision = savedInstanceState?.getLong("pending_tracker_revision") ?: -1
        pendingEntry = savedInstanceState?.getString("pending_tracker_entry")?.let { runCatching { TrackerLibraryEntry.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), (resources.configuration.screenWidthDp / (120 * resources.configuration.fontScale)).toInt().coerceIn(1, 6))
        binding.recyclerView.adapter = listAdapter
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh)
        binding.buttonLogin.setOnClickListener { router.openScrobblerSettings(viewModel.service.value) }
        binding.buttonSort.setOnClickListener {
            viewModel.toggleSort()
        }
        binding.editSearch.setText(viewModel.search.value)
        binding.editSearch.addTextChangedListener { text -> viewModel.setSearch(text?.toString().orEmpty()) }
        buildStatusTabs(binding)
        viewModel.content.observe(viewLifecycleOwner) {
            listAdapter.submitList(it)
            binding.textEmpty.isVisible = it.isEmpty() && viewModel.isConnected.value && !viewModel.isLoading.value
        }
        ScrobblerService.entries.forEach { service ->
            binding.tabsServices.addTab(binding.tabsServices.newTab().setText(service.titleResId).setTag(service))
        }
        binding.tabsServices.getTabAt(ScrobblerService.entries.indexOf(viewModel.service.value))?.select()
        binding.tabsServices.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { viewModel.selectService(tab.tag as ScrobblerService) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        viewModel.userName.observe(viewLifecycleOwner) { binding.textAccount.text = it.orEmpty() }
        viewModel.service.observe(viewLifecycleOwner) { binding.buttonLogin.text = getString(R.string.tracker_connect, getString(it.titleResId)) }
        viewModel.entries.observe(viewLifecycleOwner) { entries ->
            statusTabs.forEach { (chip, filter) ->
                val count = entries.count { filter.first == null || it.status == filter.first }
                chip.text = getString(R.string.anilist_status_count, getString(filter.second), count)
            }
        }
        viewModel.sortByTitle.observe(viewLifecycleOwner) { alphabetical ->
            binding.buttonSort.tag = alphabetical
            binding.buttonSort.setText(if (alphabetical) R.string.sort_recently_updated else R.string.sort_title)
        }
        viewModel.syncMessage.observe(viewLifecycleOwner) { (pending, error) ->
            binding.textSync.isVisible = pending > 0 || error != null
            binding.textSync.text = if (error != null) getString(R.string.tracker_sync_error, error)
                else getString(R.string.tracker_sync_pending, pending)
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.textError.isVisible = error != null
            val message = if (error is TrackerHttpException && error.code == 429) {
                getString(R.string.tracker_retry_after, (error.retryAfterMillis + 999) / 1000)
            } else error?.message.orEmpty()
            binding.textError.text = error?.let { getString(R.string.tracker_refresh_error, message) }.orEmpty()
        }
        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.buttonLogin.isVisible = !connected
            binding.recyclerView.isVisible = connected
            binding.textEmpty.isVisible = connected && viewModel.content.value.isEmpty() && !viewModel.isLoading.value
        }
        viewModel.isLoading.observe(viewLifecycleOwner) {
            binding.swipeRefresh.isRefreshing = it
            binding.textEmpty.isVisible = !it && viewModel.isConnected.value && viewModel.content.value.isEmpty()
        }
        viewModel.refreshIfStale()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingEntry?.let {
            outState.putString("pending_tracker_entry", it.toJson().toString())
            pendingAccountId?.let { account -> outState.putLong("pending_tracker_account", account) }
            outState.putLong("pending_tracker_revision", pendingRevision)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshIfStale()
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    private fun buildStatusTabs(binding: FragmentAnilistLibraryBinding) {
        statusTabs.clear()
        val statuses = listOf(
            null to R.string.anilist_all,
            "CURRENT" to R.string.status_reading,
            "COMPLETED" to R.string.status_completed,
            "PLANNING" to R.string.status_planned,
            "PAUSED" to R.string.status_on_hold,
            "DROPPED" to R.string.status_dropped,
            "REPEATING" to R.string.status_re_reading,
        )
        binding.tabsStatuses.clearOnTabSelectedListeners()
        binding.tabsStatuses.removeAllTabs()
        statuses.forEach { (value, title) ->
            val tab = binding.tabsStatuses.newTab().setText(title).setTag(value)
            statusTabs += tab to (value to title)
            binding.tabsStatuses.addTab(tab, false)
        }
        binding.tabsStatuses.getTabAt(statuses.indexOfFirst { it.first == viewModel.selectedStatus.value }.coerceAtLeast(0))?.select()
        binding.tabsStatuses.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { viewModel.setStatus(tab.tag as? String) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun onEntryClicked(entry: TrackerLibraryEntry) {
        if (entry.localMangaId != null) {
            router.openDetails(entry.localMangaId)
        } else {
            pendingEntry = entry
            val storage = viewModel.repository.scrobbler(entry.service).trackerRepository.storage
            pendingAccountId = storage.user?.id
            pendingRevision = storage.sessionVersion
            picker.launch(AppRouter.pickMangaIntent(requireContext(), entry.title)
                .putExtra(AppRouter.KEY_TRACKER_TARGET_ID, entry.mediaId)
                .putExtra(AppRouter.KEY_TRACKER_TITLE, entry.title))
        }
    }
}

private class AniListLibraryAdapter(
    private val onClick: (TrackerLibraryEntry) -> Unit,
) : ListAdapter<TrackerLibraryEntry, AniListLibraryAdapter.Holder>(object : DiffUtil.ItemCallback<TrackerLibraryEntry>() {
    override fun areItemsTheSame(oldItem: TrackerLibraryEntry, newItem: TrackerLibraryEntry) =
        oldItem.service == newItem.service && oldItem.mediaId == newItem.mediaId
    override fun areContentsTheSame(oldItem: TrackerLibraryEntry, newItem: TrackerLibraryEntry) = oldItem == newItem
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAnilistLibraryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false), onClick)
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    class Holder(private val binding: ItemAnilistLibraryEntryBinding, private val onClick: (TrackerLibraryEntry) -> Unit) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TrackerLibraryEntry) {
            binding.imageCover.setImageAsync(item.coverUrl.takeIf(String::isNotBlank), null)
            binding.textTitle.text = item.title
            binding.textProgress.text = buildString {
                append(item.progress).append(" / ").append(item.chapters?.toString() ?: "—")
                append(" • ").append(binding.root.context.getString(R.string.anilist_score, item.score))
            }
            binding.textLinkState.setText(if (item.localMangaId != null) R.string.open_in_library else R.string.search_sources_for_title)
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
