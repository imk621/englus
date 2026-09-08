package com.englus.app.material

import android.content.Context

/**
 * Whether the app is allowed to go and get material on its own.
 *
 * Separate from the exposure switch because the cost is different in kind. That
 * one governs attention - what appears uninvited on a screen. This one governs
 * data and battery: a fetch downloads tens of megabytes and then runs the
 * transcriber for several minutes, and doing that on a train on mobile data is
 * a different kind of unwelcome.
 */
class MaterialSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("material", Context.MODE_PRIVATE)

    /**
     * Off by default.
     *
     * It spends the learner's data and battery without being asked, so it waits
     * to be asked. The screen offers it rather than assuming it.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * One a day by default. Transcribing a 40-minute episode is about five
     * minutes of CPU; three is already a real dent in a battery, and nobody
     * listens to more than they can listen to.
     */
    var perDay: Int
        get() = prefs.getInt(KEY_PER_DAY, 1)
        set(value) = prefs.edit().putInt(KEY_PER_DAY, value.coerceIn(1, 3)).apply()

    var lastFetchAt: Long
        get() = prefs.getLong(KEY_LAST, 0)
        set(value) = prefs.edit().putLong(KEY_LAST, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PER_DAY = "perDay"
        const val KEY_LAST = "lastFetchAt"
    }
}
