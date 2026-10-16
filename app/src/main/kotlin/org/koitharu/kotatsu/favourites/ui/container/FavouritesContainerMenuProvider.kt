package org.koitharu.kotatsu.favourites.ui.container

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter

class FavouritesContainerMenuProvider(
	private val router: AppRouter,
	private val showCategoryChooser: () -> Boolean,
	private val onChooseCategory: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_favourites_container, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_choose_category)?.isVisible = showCategoryChooser()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_choose_category -> onChooseCategory()
			R.id.action_manage -> {
				router.openFavoriteCategories()
			}

			else -> return false
		}
		return true
	}
}
