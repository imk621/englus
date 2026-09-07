package com.englus.app

import com.englus.app.exposure.ExposureSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quiet hours are almost always set across midnight, which is exactly the case a
 * plain "from <= hour < to" gets wrong - and it gets it wrong in the direction
 * that wakes someone up at three in the morning.
 */
class ExposureTest {

    @Test
    fun quietHoursWrapAroundMidnight() {
        val from = 22
        val to = 8
        listOf(22, 23, 0, 3, 7).forEach {
            assertTrue("$it should be quiet", ExposureSettings.isQuiet(it, from, to))
        }
        listOf(8, 12, 18, 21).forEach {
            assertFalse("$it should not be quiet", ExposureSettings.isQuiet(it, from, to))
        }
    }

    @Test
    fun quietHoursWithinOneDayStillWork() {
        // Someone who wants silence only during working hours.
        listOf(9, 13, 17).forEach {
            assertTrue("$it should be quiet", ExposureSettings.isQuiet(it, 9, 18))
        }
        listOf(8, 18, 23, 0).forEach {
            assertFalse("$it should not be quiet", ExposureSettings.isQuiet(it, 9, 18))
        }
    }

    /** Equal bounds means "no quiet hours", not "quiet all day". */
    @Test
    fun equalBoundsMeansAlwaysAllowed() {
        (0..23).forEach {
            assertFalse("$it was silenced by an empty window", ExposureSettings.isQuiet(it, 0, 0))
            assertFalse("$it was silenced by an empty window", ExposureSettings.isQuiet(it, 22, 22))
        }
    }

    @Test
    fun boundsAreHalfOpenSoTheEndHourIsAwake() {
        // 22-8 must not silence 8 o'clock itself, or the morning is lost.
        assertFalse(ExposureSettings.isQuiet(8, 22, 8))
        assertTrue(ExposureSettings.isQuiet(22, 22, 8))
    }
}
