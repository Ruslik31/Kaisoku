package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class ReaderChapterActionsTest {
    @Test fun actualHapticObserverSkipsDisabledSnapshotsAndSeedsAfterPause() = onMain {
        val owner = TestOwner()
        val view = RecordingView(context())
        var enabled = false
        var chapter: PresentedChapter? = PresentedChapter(1, 1)
        var snapshots = 0
        val helper = ReaderChapterHaptics(view, owner.lifecycle, { enabled }) { snapshots++; chapter }
        owner.resume()
        helper.onPreDraw()
        assertEquals(0, snapshots)
        enabled = true
        helper.onPreDraw()
        chapter = PresentedChapter(1, 2)
        helper.onPreDraw()
        repeat(3) { helper.onPreDraw() }
        assertEquals(listOf(HapticFeedbackConstants.CLOCK_TICK), view.requests)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        chapter = PresentedChapter(1, 3)
        helper.onPreDraw()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        helper.onPreDraw()
        assertEquals(1, view.requests.size)
        chapter = PresentedChapter(1, 4)
        helper.onPreDraw()
        assertEquals(2, view.requests.size)
        owner.destroy()
    }

    @Test fun actualHapticObserverDoesNotQueueUnfocusedOrRecreatedChapterEffects() = onMain {
        val owner = TestOwner()
        val view = RecordingView(context())
        var chapter: PresentedChapter? = PresentedChapter(1, 1)
        val helper = ReaderChapterHaptics(view, owner.lifecycle, { true }) { chapter }
        owner.resume()
        helper.onPreDraw()
        view.focused = false
        chapter = PresentedChapter(1, 2)
        helper.onPreDraw()
        view.focused = true
        helper.onPreDraw()
        assertTrue(view.requests.isEmpty())

        // A chapter still awaiting presentation behind the sheet counts after dismissal.
        view.focused = false
        chapter = null
        helper.onPreDraw()
        view.focused = true
        chapter = PresentedChapter(1, 3)
        helper.onPreDraw()
        assertEquals(1, view.requests.size)
        owner.destroy()

        val recreated = TestOwner()
        val recreatedHelper = ReaderChapterHaptics(view, recreated.lifecycle, { true }) { chapter }
        recreated.resume()
        repeat(3) { recreatedHelper.onPreDraw() }
        assertEquals(1, view.requests.size)
        recreated.destroy()
    }

    @Test fun readerMenusKeepTheirExistingMappingsAndHapticsDefaultOff() = onMain {
        val context = context()
        val manga = PopupMenu(context, View(context)).apply { inflate(R.menu.opt_reader) }.menu
        val novel = PopupMenu(context, View(context)).apply { inflate(R.menu.opt_novel_reader) }.menu
        assertNotNull(manga.findItem(R.id.action_open_chapter_browser))
        assertNotNull(manga.findItem(R.id.action_info))
        assertNotNull(novel.findItem(R.id.action_open_chapter_browser))
        for (id in intArrayOf(R.id.action_chapters, R.id.action_settings, R.id.action_novel_translate,
            R.id.action_novel_read_aloud, R.id.action_novel_source_settings)) assertNotNull(novel.findItem(id))
        context.resources.getXml(R.xml.pref_reader).use { xml ->
            var defaultValue: String? = null
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG &&
                    xml.getAttributeValue(ANDROID, "key") == "reader_chapter_haptics") {
                    defaultValue = xml.getAttributeValue(ANDROID, "defaultValue")
                }
            }
            assertEquals("false", defaultValue)
        }
    }

    private class RecordingView(context: Context) : View(context) {
        var focused = true
        val requests = ArrayList<Int>()
        override fun isShown(): Boolean = true
        override fun hasWindowFocus(): Boolean = focused
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            requests += feedbackConstant
            return false // Unsupported hardware must not trigger a retry.
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private fun context() = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Kotatsu)
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
