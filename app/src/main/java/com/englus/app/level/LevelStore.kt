package com.englus.app.level

import android.content.Context
import com.englus.app.vocab.Cefr

/**
 * The last measured level.
 *
 * Kept apart from the vocabulary profile because the two answer different
 * questions. The profile is evidence accumulated from work actually done and it
 * only ever sees words that turned up in the material; this is a deliberate
 * sample across the whole frequency range, taken in one sitting. The profile is
 * better once it exists, but it says nothing on day one - and day one is when a
 * learner most needs to be pointed at the right material.
 */
class LevelStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("level", Context.MODE_PRIVATE)

    val measured: Boolean get() = prefs.contains(KEY_SIZE)

    var vocabularySize: Int
        get() = prefs.getInt(KEY_SIZE, 0)
        private set(v) = prefs.edit().putInt(KEY_SIZE, v).apply()

    var takenAt: Long
        get() = prefs.getLong(KEY_TAKEN, 0)
        private set(v) = prefs.edit().putLong(KEY_TAKEN, v).apply()

    var falseAlarmRate: Float
        get() = prefs.getFloat(KEY_FALSE_ALARM, 0f)
        private set(v) = prefs.edit().putFloat(KEY_FALSE_ALARM, v).apply()

    val cefr: Cefr?
        get() = if (!measured) null else LevelTest.cefrForSize(vocabularySize)

    fun save(result: LevelResult) {
        prefs.edit()
            .putInt(KEY_SIZE, result.vocabularySize)
            .putLong(KEY_TAKEN, result.takenAt)
            .putFloat(KEY_FALSE_ALARM, result.falseAlarmRate)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /**
     * Vocabulary moves slowly, so a nudge to retake belongs on the scale of
     * months. Retaking every week would mostly measure the noise in one sitting.
     */
    val stale: Boolean
        get() = measured && System.currentTimeMillis() - takenAt > 90L * 24 * 3_600_000

    private companion object {
        const val KEY_SIZE = "vocabularySize"
        const val KEY_TAKEN = "takenAt"
        const val KEY_FALSE_ALARM = "falseAlarm"
    }
}
