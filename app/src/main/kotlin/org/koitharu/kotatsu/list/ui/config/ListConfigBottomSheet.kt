package org.koitharu.kotatsu.list.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.databinding.SheetListModeBinding

@AndroidEntryPoint
class ListConfigBottomSheet :
	BaseAdaptiveSheet<SheetListModeBinding>(),
	Slider.OnChangeListener,
	MaterialButtonToggleGroup.OnButtonCheckedListener, CompoundButton.OnCheckedChangeListener,
	AdapterView.OnItemSelectedListener {

	private val viewModel by viewModels<ListConfigViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetListModeBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetListModeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val mode = viewModel.listMode
		binding.buttonList.isChecked = mode == ListMode.LIST
		binding.buttonListDetailed.isChecked = mode == ListMode.DETAILED_LIST
		binding.buttonGrid.isChecked = mode == ListMode.GRID
		binding.buttonCoverOnly.isChecked = mode == ListMode.COVER_ONLY
		val isGridMode = mode == ListMode.GRID || mode == ListMode.COVER_ONLY
		binding.textViewGridTitle.isVisible = isGridMode
		binding.sliderGrid.isVisible = isGridMode

		binding.sliderGrid.setLabelFormatter(IntPercentLabelFormatter(binding.root.context))
		binding.sliderGrid.setValueRounded(viewModel.gridSize.toFloat())
		binding.sliderGrid.addOnChangeListener(this)

		binding.checkableGroup.addOnButtonCheckedListener(this)

		binding.switchGrouping.isVisible = viewModel.isGroupingSupported
		if (viewModel.isGroupingSupported) {
			binding.switchGrouping.isEnabled = viewModel.isGroupingAvailable
		}
		binding.switchGrouping.isChecked = viewModel.isGroupingEnabled
		binding.switchGrouping.setOnCheckedChangeListener(this)

		val sortTypes = viewModel.getSortTypes()
		val sortOrders = viewModel.getSortOrders()
		if (sortTypes != null || sortOrders != null) {
			binding.textViewOrderTitle.isVisible = true
			val current = viewModel.getSelectedSortOrder()
			val labels = sortTypes?.map { binding.root.context.getString(it.titleResId) }
				?: sortOrders.orEmpty().map { binding.root.context.getString(it.titleResId) }
			binding.spinnerOrder.adapter = ArrayAdapter(
				binding.spinnerOrder.context,
				android.R.layout.simple_spinner_dropdown_item,
				android.R.id.text1,
				labels,
			)
			val selected = sortTypes?.indexOf(current?.type) ?: sortOrders.orEmpty().indexOf(current)
			if (selected >= 0) {
				binding.spinnerOrder.setSelection(selected, false)
			}
			binding.spinnerOrder.onItemSelectedListener = this
			binding.spinnerDirection.isVisible = sortTypes != null
			if (sortTypes != null) {
				binding.spinnerDirection.adapter = ArrayAdapter(
					binding.root.context,
					android.R.layout.simple_spinner_dropdown_item,
					android.R.id.text1,
					listOf(
						binding.root.context.getString(R.string.sort_order_asc),
						binding.root.context.getString(R.string.sort_order_desc),
					),
				)
				binding.spinnerDirection.setSelection(if (current?.isAscending == true) 0 else 1, false)
				binding.spinnerDirection.onItemSelectedListener = this
			}
			binding.cardOrder.isVisible = true
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onButtonChecked(group: MaterialButtonToggleGroup?, checkedId: Int, isChecked: Boolean) {
		if (!isChecked) {
			return
		}
		val mode = when (checkedId) {
			R.id.button_list -> ListMode.LIST
			R.id.button_list_detailed -> ListMode.DETAILED_LIST
			R.id.button_grid -> ListMode.GRID
			R.id.button_cover_only -> ListMode.COVER_ONLY
			else -> return
		}
		val isGridMode = mode == ListMode.GRID || mode == ListMode.COVER_ONLY
		requireViewBinding().textViewGridTitle.isVisible = isGridMode
		requireViewBinding().sliderGrid.isVisible = isGridMode
		viewModel.listMode = mode
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		when (buttonView.id) {
			R.id.switch_grouping -> viewModel.isGroupingEnabled = isChecked
		}
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			viewModel.gridSize = value.toInt()
		}
	}

	override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
		when (parent.id) {
			R.id.spinner_order -> {
				if (viewModel.getSortTypes() != null) {
					viewModel.setSortType(position)
				} else {
					viewModel.setSortOrder(position)
				}
				viewBinding?.switchGrouping?.isEnabled = viewModel.isGroupingAvailable
			}

			R.id.spinner_direction -> {
				viewModel.setSortAscending(position == 0)
				viewBinding?.switchGrouping?.isEnabled = viewModel.isGroupingAvailable
			}
		}
	}

	override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
