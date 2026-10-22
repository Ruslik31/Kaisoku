package org.koitharu.kotatsu.reader.ui

import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails

/** Resolves a website link only after an explicit reader action. */
internal class ReaderChapterBrowser(
    private val activity: FragmentActivity,
    private val host: View,
    private val repositoryFactory: MangaRepository.Factory,
    private val currentTarget: () -> ChapterBrowserTarget?,
) : DefaultLifecycleObserver {
    private var resolution: Job? = null

    val isAvailable: Boolean
        get() = resolution?.isActive != true && currentTarget() != null

    init {
        activity.lifecycle.addObserver(this)
    }

    fun open() {
        if (resolution?.isActive == true) return
        val target = currentTarget() ?: return
        val request = ChapterBrowserRequest(target.key)
        val job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val url = withContext(Dispatchers.IO) {
                    request.resolve {
                        repositoryFactory.create(target.chapter.source).getChapterUrl(target.manga, target.chapter)
                    }
                }
                if (!canOpen(request)) return@launch
                if (url == null) {
                    Snackbar.make(host, R.string.reader_chapter_browser_unavailable, Snackbar.LENGTH_SHORT).show()
                } else {
                    activity.router.openBrowser(url, target.chapter.source, target.manga.title)
                }
            } catch (e: TimeoutCancellationException) {
                if (canOpen(request)) showError(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (canOpen(request)) showError(e)
            } finally {
                if (resolution === currentCoroutineContext()[Job]) resolution = null
                activity.invalidateOptionsMenu()
            }
        }
        resolution = job
        activity.invalidateOptionsMenu()
        job.start()
    }

    override fun onPause(owner: LifecycleOwner) {
        resolution?.cancel()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        resolution?.cancel()
        owner.lifecycle.removeObserver(this)
    }

    private fun canOpen(request: ChapterBrowserRequest): Boolean = request.canOpen(
        current = currentTarget()?.key,
        resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        focused = host.hasWindowFocus(),
    )

    private fun showError(error: Exception) {
        Snackbar.make(host, error.message ?: activity.getString(R.string.error_occurred), Snackbar.LENGTH_LONG)
            .setAction(R.string.copy) {
                activity.copyToClipboard(activity.getString(R.string.error), error.getCopyableErrorDetails())
                Toast.makeText(activity, R.string.error_copied, Toast.LENGTH_SHORT).show()
            }.show()
    }
}
