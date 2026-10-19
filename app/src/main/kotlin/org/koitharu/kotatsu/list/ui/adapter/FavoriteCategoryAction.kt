package org.koitharu.kotatsu.list.ui.adapter

import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.findActivity

/** Reset every binding, including empty lists, to avoid recycled actions targeting an earlier title. */
internal fun TextView.bindFavoriteCategories(values: List<FavouriteCategory>) {
    text = values.joinToString(", ") { it.title }
    isVisible = values.isNotEmpty()
    setOnClickListener(if (values.isEmpty()) null else android.view.View.OnClickListener {
        val activity = context.findActivity() as? FragmentActivity ?: return@OnClickListener
        if (values.size == 1) activity.router.openFavorites(values.single())
        else MaterialAlertDialogBuilder(context)
            .setTitle(R.string.choose_favorites_category)
            .setItems(values.map { it.title }.toTypedArray()) { _, position -> activity.router.openFavorites(values[position]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    })
}
