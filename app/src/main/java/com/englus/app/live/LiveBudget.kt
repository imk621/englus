package com.englus.app.live

import android.content.Context
import java.util.Calendar

/**
 * A hard ceiling on the one feature that costs real money.
 *
 * Everything else in this app is free once it is installed - whisper, ML Kit
 * translation, OCR - and the text calls to Gemini are cheap enough that a daily
 * cap would be theatre. A live audio session is not: it bills for every second
 * in both directions, it runs while the learner is talking rather than in short
 * bursts, and the natural failure is leaving it open.
 *
 * So the limit is enforced in the app rather than left to the console, it is
 * checked before connecting *and* while running, and the remaining time is on
 * screen the whole session. A budget the learner cannot see is one they will
 * only discover from a bill.
 */
class LiveBudget(context: Context) {

    private val prefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)

    /** Minutes per day. Zero disables live conversation entirely. */
    var dailyMinutes: Int
        get() = prefs.getInt(KEY_LIMIT, DEFAULT_MINUTES)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(0, 120)).apply()

    /** Seconds already spent today; resets on the calendar day, not 24h from first use. */
    val spentSeconds: Int
        get() = if (prefs.getInt(KEY_DAY, -1) == today()) prefs.getInt(KEY_SPENT, 0) else 0

    val remainingSeconds: Int
        get() = (dailyMinutes * 60 - spentSeconds).coerceAtLeast(0)

    val exhausted: Boolean get() = remainingSeconds <= 0

    /**
     * Records time as it is used rather than at the end.
     *
     * A session that is killed - the process dies, the phone sleeps, the network
     * drops - still cost money, and charging only on a clean exit would make the
     * budget trivially escapable by accident.
     */
    fun spend(seconds: Int) {
        if (seconds <= 0) return
        prefs.edit()
            .putInt(KEY_DAY, today())
            .putInt(KEY_SPENT, spentSeconds + seconds)
            .apply()
    }

    private fun today(): Int = Calendar.getInstance().let {
        it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR)
    }

    private companion object {
        const val KEY_LIMIT = "dailyMinutes"
        const val KEY_DAY = "day"
        const val KEY_SPENT = "spentSeconds"

        /**
         * Off until asked for.
         *
         * This is the only feature in the app that spends money, and it was
         * armed out of the box with ten minutes a day. A paid feature that is
         * on by default is one someone can start paying for without ever having
         * decided to. Ten minutes is still the first thing offered once they do
         * decide - long enough for a real conversation, short enough that
         * forgetting to hang up is an annoyance rather than an expense.
         */
        const val DEFAULT_MINUTES = 0
        const val SUGGESTED_MINUTES = 10
    }
}
