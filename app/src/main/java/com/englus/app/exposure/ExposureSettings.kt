package com.englus.app.exposure

import android.content.Context
import java.util.Calendar

/**
 * The off switch for everything that pushes English at the learner.
 *
 * Background listening and hands-free shadowing do not belong here: those only
 * make noise because someone pressed play, and the way to stop them is the pause
 * button. What needs governing is the surfaces that appear uninvited - the home
 * screen widget and the notifications - because those show up during meetings,
 * at dinner, and at two in the morning.
 *
 * There are three levels of "not now" and they are not the same thing:
 *
 *   - [snoozeUntil] is the common one. The learner is in a meeting; they want it
 *     back this afternoon. Offering only a permanent switch means they turn it
 *     off and never turn it back on.
 *   - Quiet hours cover the case nobody remembers to handle in advance, which is
 *     the phone lighting up in the middle of the night.
 *   - [enabled] is the real off, for when ambient exposure is simply not wanted.
 *
 * An ambient feature that cannot be silenced does not get tolerated; it gets the
 * app uninstalled.
 */
class ExposureSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("exposure", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** English sentences in notifications rather than a bare errand count. */
    var notifySentences: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    /** Sentences on the home screen widget. Off shows a blank face instead. */
    var widgetSentences: Boolean
        get() = prefs.getBoolean(KEY_WIDGET, true)
        set(value) = prefs.edit().putBoolean(KEY_WIDGET, value).apply()

    var quietFromHour: Int
        get() = prefs.getInt(KEY_QUIET_FROM, 22)
        set(value) = prefs.edit().putInt(KEY_QUIET_FROM, value.coerceIn(0, 23)).apply()

    var quietToHour: Int
        get() = prefs.getInt(KEY_QUIET_TO, 8)
        set(value) = prefs.edit().putInt(KEY_QUIET_TO, value.coerceIn(0, 23)).apply()

    var snoozeUntil: Long
        get() = prefs.getLong(KEY_SNOOZE, 0)
        private set(value) = prefs.edit().putLong(KEY_SNOOZE, value).apply()

    val snoozed: Boolean get() = System.currentTimeMillis() < snoozeUntil

    fun snooze(hours: Int) {
        snoozeUntil = System.currentTimeMillis() + hours * 3_600_000L
    }

    /** Until the next local midnight, for "not for the rest of today". */
    fun snoozeForToday() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        snoozeUntil = cal.timeInMillis
    }

    fun resume() {
        snoozeUntil = 0
    }

    fun inQuietHours(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean =
        isQuiet(hour, quietFromHour, quietToHour)

    /** Whether anything is allowed to appear uninvited right now. */
    val active: Boolean get() = enabled && !snoozed && !inQuietHours()

    /** Why it is off, for the screen to explain rather than just show a dead toggle. */
    fun status(): String = when {
        !enabled -> "꺼져 있습니다"
        snoozed -> "잠시 멈춤"
        inQuietHours() -> "방해 금지 시간 (${quietFromHour}시~${quietToHour}시)"
        else -> "켜져 있습니다"
    }

    companion object {
        /**
         * Quiet hours almost always wrap midnight (22 to 8), so a plain range
         * check is wrong for the case that matters most - the phone lighting up
         * at three in the morning.
         */
        fun isQuiet(hour: Int, from: Int, to: Int): Boolean {
            if (from == to) return false
            return if (from < to) hour in from until to else hour >= from || hour < to
        }

        const val KEY_ENABLED = "enabled"
        const val KEY_NOTIFY = "notifySentences"
        const val KEY_WIDGET = "widgetSentences"
        const val KEY_QUIET_FROM = "quietFrom"
        const val KEY_QUIET_TO = "quietTo"
        const val KEY_SNOOZE = "snoozeUntil"
    }
}
