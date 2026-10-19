package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import org.koitharu.kotatsu.core.util.ext.findActivity
import java.lang.ref.WeakReference

class TitleScrollCoordinator(
    private val titleView: TextView,
) : NestedScrollView.OnScrollChangeListener {

    private val location = IntArray(2)
    private var activityRef: WeakReference<AppCompatActivity>? = null

    override fun onScrollChange(v: NestedScrollView, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {
        updateTitle(v)
    }

    private fun updateTitle(v: View) {
        val actionBar = getActivity(v.context)?.supportActionBar ?: return
        if (!titleView.isAttachedToWindow) {
            actionBar.setDisplayShowTitleEnabled(true)
            return
        }
        titleView.getLocationOnScreen(location)
        var top = location[1] + titleView.height
        v.getLocationOnScreen(location)
        top -= location[1]
        actionBar.setDisplayShowTitleEnabled(top < 0)
    }

    fun attach(scrollView: NestedScrollView) {
        scrollView.setOnScrollChangeListener(this)
        scrollView.doOnLayout {
            onScrollChange(scrollView, 0, 0, 0, 0)
        }
    }

    fun attach(recyclerView: RecyclerView) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) = updateTitle(view)
        })
        recyclerView.doOnLayout { updateTitle(it) }
    }

    private fun getActivity(context: Context): AppCompatActivity? {
        activityRef?.get()?.let {
            if (!it.isDestroyed) return it
        }
        val activity = context.findActivity() as? AppCompatActivity
        if (activity == null || activity.isDestroyed) {
            return null
        }
        activityRef = WeakReference(activity)
        return activity
    }
}
