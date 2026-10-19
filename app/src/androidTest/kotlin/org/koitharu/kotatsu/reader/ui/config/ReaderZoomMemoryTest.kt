package org.koitharu.kotatsu.reader.ui.config

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.reader.ui.pager.doublepage.DoublePageScalingFrame
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonScalingFrame
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonRecyclerView
import java.util.UUID
import org.json.JSONObject
import org.koitharu.kotatsu.core.util.ext.putAll

@RunWith(AndroidJUnit4::class)
class ReaderZoomMemoryTest {
    @Test fun scopesPersistSeparatelyAndDisablingKeepsSavedValuesWithoutRecording() = fixture { context, prefs ->
        val memory = ReaderZoomMemory(context)
        assertFalse(memory.isEnabled)
        memory.record(ReaderZoomScope.STANDARD, 2f)
        assertFalse(prefs.contains(ReaderZoomMemory.key(ReaderZoomScope.STANDARD)))
        prefs.edit().putBoolean(ReaderZoomMemory.KEY_ENABLED, true).commit()
        for ((index, mode) in ReaderZoomScope.entries.withIndex()) memory.record(mode, 1f + index)
        val recreated = ReaderZoomMemory(context)
        for ((index, mode) in ReaderZoomScope.entries.withIndex()) assertEquals(1f + index, recreated.get(mode))
        prefs.edit().putBoolean(ReaderZoomMemory.KEY_ENABLED, false).commit()
        memory.record(ReaderZoomScope.STANDARD, 9f)
        assertNull(memory.get(ReaderZoomScope.STANDARD))
        prefs.edit().putBoolean(ReaderZoomMemory.KEY_ENABLED, true).commit()
        assertEquals(1f, memory.get(ReaderZoomScope.STANDARD))
    }

    @Test fun malformedImportedValuesFallBackAndLeaveUnrelatedSettingsAlone() = fixture { context, prefs ->
        prefs.edit().putBoolean(ReaderZoomMemory.KEY_ENABLED, true)
            .putFloat(ReaderZoomMemory.key(ReaderZoomScope.STANDARD), Float.NaN)
            .putString(ReaderZoomMemory.key(ReaderZoomScope.REVERSED), "two")
            .putString("existing", "keep").commit()
        val memory = ReaderZoomMemory(context)
        assertNull(memory.get(ReaderZoomScope.STANDARD))
        assertNull(memory.get(ReaderZoomScope.REVERSED))
        prefs.edit().putString(ReaderZoomMemory.KEY_ENABLED, "true").commit()
        ReaderZoomMemory.normalizePreferences(prefs)
        assertFalse(memory.isEnabled)
        assertEquals("keep", prefs.getString("existing", null))
    }

    @Test fun nativeJsonWholeNumberZoomRestoreIsMigratedOnceWithoutChangingOtherSettings() = fixture { context, prefs ->
        val key = ReaderZoomMemory.key(ReaderZoomScope.STANDARD)
        val decoded = JSONObject(JSONObject(mapOf(key to 2f)).toString()).get(key)
        prefs.edit().apply {
            putBoolean(ReaderZoomMemory.KEY_ENABLED, true)
            putString("unrelated", "keep")
            putAll(mapOf(key to decoded))
        }.commit()
        assertEquals(2f, ReaderZoomMemory(context).get(ReaderZoomScope.STANDARD))
        assertTrue(prefs.all[key] is Float)
        assertEquals("keep", prefs.getString("unrelated", null))
    }

    @Test fun programmaticSpreadZoomWaitsForGeometryAndDoesNotRecordAUserGesture() = fixture { context, _ ->
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val frame = DoublePageScalingFrame(context)
            val child = FrameLayout(context)
            frame.addView(child)
            var recorded = 0
            frame.onUserZoomChanged = { recorded++ }
            frame.zoom = 2f
            size(frame)
            assertEquals(2f, frame.zoom)
            assertEquals(2f, child.scaleX)
            frame.zoom = Float.NaN
            assertEquals(2f, frame.zoom)
            assertEquals(0, recorded)
        }
    }

    @Test fun webtoonProgrammaticRestoreAndDisableDoNotBecomeUserZoom() = fixture { context, _ ->
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val frame = WebtoonScalingFrame(context)
            frame.addView(WebtoonRecyclerView(context))
            var recorded = 0
            frame.onUserZoomChanged = { recorded++ }
            frame.isZoomEnable = true
            frame.zoom = 1.5f
            size(frame)
            assertEquals(1.5f, frame.zoom)
            frame.isZoomEnable = true
            assertEquals(1.5f, frame.zoom)
            frame.isZoomEnable = false
            assertEquals(1f, frame.zoom)
            frame.onZoomIn()
            assertEquals(1f, frame.zoom)
            assertEquals(0, recorded)
        }
    }

    private fun size(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 1200)
    }

    private fun fixture(block: (Context, SharedPreferences) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "reader-zoom-fixture-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        try { block(context, prefs) } finally { prefs.edit().clear().commit() }
    }
}
