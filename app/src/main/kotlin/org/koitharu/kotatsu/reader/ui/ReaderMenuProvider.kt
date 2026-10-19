package org.koitharu.kotatsu.reader.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

class ReaderMenuProvider(
    private val canOpenChapter: () -> Boolean,
    private val openChapter: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_reader, menu)
	}

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_open_chapter_browser)?.isEnabled = canOpenChapter()
    }

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
            R.id.action_open_chapter_browser -> {
                openChapter()
                true
            }

			R.id.action_info -> {
				// TODO
				true
			}

			else -> false
		}
	}
}
