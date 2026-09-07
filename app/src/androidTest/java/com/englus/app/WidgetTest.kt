package com.englus.app

import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.exposure.ExposureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A widget layout fails quietly.
 *
 * RemoteViews only supports a fixed set of view types, and an unsupported one
 * does not fail at build time - it produces a blank rectangle on the home screen
 * that nobody can debug from a screenshot. Inflating it here is the cheapest way
 * to find that out.
 */
@RunWith(AndroidJUnit4::class)
class WidgetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun layoutInflatesThroughRemoteViews() {
        val views = RemoteViews(context.packageName, R.layout.widget_sentence)
        views.setTextViewText(R.id.widget_label, "VOA")
        views.setTextViewText(R.id.widget_english, "The market opened early")
        views.setTextViewText(R.id.widget_korean, "시장이 일찍 열렸다")

        val root = views.apply(context, FrameLayout(context))

        assertEquals("VOA", root.findViewById<TextView>(R.id.widget_label).text)
        assertEquals(
            "The market opened early",
            root.findViewById<TextView>(R.id.widget_english).text,
        )
        assertEquals("시장이 일찍 열렸다", root.findViewById<TextView>(R.id.widget_korean).text)
    }

    /**
     * The off switch has to be immediate and it has to survive a restart, which
     * is what makes it trustworthy enough to leave the feature on at all.
     */
    @Test
    fun exposureSwitchesPersistAndGate() {
        val settings = ExposureSettings(context)
        val originalEnabled = settings.enabled
        val originalFrom = settings.quietFromHour
        val originalTo = settings.quietToHour
        try {
            settings.enabled = true
            settings.quietFromHour = 0
            settings.quietToHour = 0
            settings.resume()
            assertTrue("nothing should be blocking", ExposureSettings(context).active)

            settings.snoozeForToday()
            assertTrue(ExposureSettings(context).snoozed)
            assertFalse("a snooze must silence it", ExposureSettings(context).active)

            settings.resume()
            assertTrue(ExposureSettings(context).active)

            settings.enabled = false
            // A fresh instance: the setting has to be on disk, not in memory.
            assertFalse("the off switch did not persist", ExposureSettings(context).active)
        } finally {
            settings.enabled = originalEnabled
            settings.quietFromHour = originalFrom
            settings.quietToHour = originalTo
            settings.resume()
        }
    }
}
