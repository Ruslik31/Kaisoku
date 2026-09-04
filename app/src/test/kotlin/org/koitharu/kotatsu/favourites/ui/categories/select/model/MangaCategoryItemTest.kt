package org.koitharu.kotatsu.favourites.ui.categories.select.model

import com.google.android.material.checkbox.MaterialCheckBox
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback

class MangaCategoryItemTest {

	private fun category(id: Long) = FavouriteCategory(
		id = id,
		title = "Category $id",
		sortKey = id.toInt(),
		order = ListSortOrder.ALPHABETIC,
		createdAt = Instant.EPOCH,
		isTrackingEnabled = false,
		isVisibleInLibrary = true,
	)

	private fun item(id: Long, checkedState: Int, addedAt: Instant? = null) = MangaCategoryItem(
		category = category(id),
		checkedState = checkedState,
		isTrackerEnabled = false,
		addedAt = addedAt,
	)

	@Test
	fun sameIdIsSameItem() {
		val a = item(1, MaterialCheckBox.STATE_UNCHECKED)
		val b = item(1, MaterialCheckBox.STATE_CHECKED, Instant.ofEpochMilli(1000))
		assertEquals(true, a.areItemsTheSame(b))
	}

	@Test
	fun addedAtChangeIsFullRebind() {
		val before = item(1, MaterialCheckBox.STATE_UNCHECKED)
		val after = item(1, MaterialCheckBox.STATE_UNCHECKED, Instant.ofEpochMilli(1000))
		assertNull(before.getChangePayload(after))
	}

	@Test
	fun checkedStateChangeKeepsPayload() {
		val before = item(1, MaterialCheckBox.STATE_UNCHECKED)
		val after = item(1, MaterialCheckBox.STATE_CHECKED)
		assertEquals(
			ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED,
			before.getChangePayload(after),
		)
	}

	@Test
	fun multiSelectHasNoAddedAt() {
		// The ViewModel passes null when more than one manga is being categorized
		assertNull(item(1, MaterialCheckBox.STATE_UNCHECKED).addedAt)
	}
}
