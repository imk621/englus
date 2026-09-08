package com.englus.app

import com.englus.app.daily.DailyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The date key decides whether a day gets generated once or never again.
 *
 * Both failure modes are quiet: a key that never changes means the set is built
 * once and the feature silently dies, and a key that changes within a day means
 * a new request every time the screen opens.
 */
class DailyKeyTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun sameDayIsOneKey() {
        val morning = DailyRepository.todayKey(at(2026, 9, 9, 7))
        val night = DailyRepository.todayKey(at(2026, 9, 9, 23))
        assertEquals("a day was split in two", morning, night)
    }

    @Test
    fun differentDaysAreDifferentKeys() {
        val today = DailyRepository.todayKey(at(2026, 9, 9, 12))
        val tomorrow = DailyRepository.todayKey(at(2026, 9, 10, 12))
        assertTrue("tomorrow did not advance", tomorrow > today)
    }

    /** Month and year rollovers are where a packed integer usually breaks. */
    @Test
    fun rollsOverMonthsAndYears() {
        assertTrue(
            DailyRepository.todayKey(at(2026, 10, 1, 9)) >
                DailyRepository.todayKey(at(2026, 9, 30, 9))
        )
        assertTrue(
            DailyRepository.todayKey(at(2027, 1, 1, 9)) >
                DailyRepository.todayKey(at(2026, 12, 31, 9))
        )
    }

    @Test
    fun keyIsReadableAsADate() {
        assertEquals(20260909, DailyRepository.todayKey(at(2026, 9, 9, 12)))
        assertEquals(20261231, DailyRepository.todayKey(at(2026, 12, 31, 12)))
    }
}
