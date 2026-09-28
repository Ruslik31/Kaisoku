package org.koitharu.kotatsu.settings.sources.manage

import android.content.Context
import androidx.room.InvalidationTracker
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.TABLE_SOURCES
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.settings.sources.model.SourceConfigItem
import javax.inject.Inject

@ViewModelScoped
class SourcesListProducer @Inject constructor(
	lifecycle: ViewModelLifecycle,
	@LocalizedAppContext private val context: Context,
	private val repository: MangaSourcesRepository,
	private val settings: AppSettings,
) : InvalidationTracker.Observer(TABLE_SOURCES) {

	private val scope = lifecycle.lifecycleScope
	private var query: String = ""
	val list = MutableStateFlow(emptyList<SourceConfigItem>())
	private val refreshGate = DeferredRefreshGate()

	private var job = scope.launch(Dispatchers.Default) {
		val snapshot = buildList()
		ensureActive()
		refreshGate.publishIfAllowed { list.value = snapshot }
	}

	init {
		settings.observeChanges()
			.filter {
				it == AppSettings.KEY_TIPS_CLOSED ||
					it == AppSettings.KEY_DISABLE_NSFW ||
					it == AppSettings.KEY_SOURCES_HIDE_BROKEN
			}
			.flowOn(Dispatchers.Default)
			.onEach { onInvalidated(emptySet()) }
			.launchIn(scope)
	}

	override fun onInvalidated(tables: Set<String>) {
		requestRefresh()
	}

	/** Defer list replacement while the user is dragging source rows. */
	fun beginSourceOrderInteraction() {
		refreshGate.pause()
		job.cancel()
		refreshGate.requestRefresh()
	}

	/** Apply one coalesced refresh after an interaction ends. Safe to call more than once. */
	fun endSourceOrderInteraction() {
		if (refreshGate.resume()) refreshList()
	}

	private fun requestRefresh() {
		if (refreshGate.requestRefresh()) refreshList()
	}

	private fun refreshList() {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob.cancelAndJoin()
			val snapshot = buildList()
			ensureActive()
			refreshGate.publishIfAllowed { list.value = snapshot }
		}
	}

	fun setQuery(value: String) {
		this.query = value
		onInvalidated(emptySet())
	}

	private suspend fun buildList(): List<SourceConfigItem> {
		val enabledSources = repository.getEnabledSources().filterNot { it.unwrap() is ExternalMangaSource }
		val pinned = repository.getPinnedSources().mapToSet { it.name }
		val isNsfwDisabled = settings.isNsfwContentDisabled
		val isReorderAvailable = settings.sourcesSortOrder == SourcesSortOrder.MANUAL
		val isDisableAvailable = !settings.isAllSourcesEnabled
		val withTip = isReorderAvailable && settings.isTipEnabled(TIP_REORDER)
		val enabledSet = enabledSources.toSet()
		if (query.isNotEmpty()) {
			return enabledSources.mapNotNull {
				if (!it.getTitle(context).contains(query, ignoreCase = true)) {
					return@mapNotNull null
				}
				SourceConfigItem.SourceItem(
					source = it,
					isEnabled = it in enabledSet,
					isDraggable = false,
					isAvailable = !isNsfwDisabled || !it.isNsfw(),
					isPinned = it.name in pinned,
					isDisableAvailable = isDisableAvailable,
				)
			}.ifEmpty {
				listOf(SourceConfigItem.EmptySearchResult)
			}
		}
		val result = ArrayList<SourceConfigItem>(enabledSources.size + 1)
		if (enabledSources.isNotEmpty()) {
			if (withTip) {
				result += SourceConfigItem.Tip(
					TIP_REORDER,
					R.drawable.ic_tap_reorder,
					R.string.sources_reorder_tip,
				)
			}
			enabledSources.mapTo(result) {
				SourceConfigItem.SourceItem(
					source = it,
					isEnabled = true,
					isDraggable = isReorderAvailable,
					isAvailable = false,
					isPinned = it.name in pinned,
					isDisableAvailable = isDisableAvailable,
				)
			}
		}
		return result
	}

	companion object {

		const val TIP_REORDER = "src_reorder"
	}
}
