package com.englus.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A sentence built for this learner, for today.
 *
 * Everything else in this app is made of material the learner actually met: a
 * podcast they chose, a sentence they failed to hear. That is deliberate, and
 * generated sentences do not belong in the listening half at all - they have no
 * audio, and a synthesised voice has none of the reductions and linking that
 * are the whole difficulty.
 *
 * Production is the other case. Writing and speaking need no source recording,
 * because the learner makes the sound. And there generation does the one thing
 * real material cannot: it can be built *around* the words this person keeps
 * missing and the mistakes they keep repeating, which is not something a
 * podcast episode will ever do on purpose.
 *
 * One row carries all of it. [korean] is the task, [english] is the model
 * answer, and the same pair serves the writing prompt, the speaking target and
 * the sentence shown on the home screen - so a day costs one request.
 */
@Entity(tableName = "daily_sentences", indices = [Index("dateKey"), Index("createdAt")])
data class DailySentence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyymmdd, so "today's set" is a cheap query and a day is generated once. */
    val dateKey: Int,
    val position: Int,
    /** The task: what to say or write, in Korean. */
    val korean: String,
    /** A natural English rendering, used as the model answer and speaking target. */
    val english: String,
    /** The learner's own weak words this was built around, comma separated. */
    val targetWords: String,
    /** Why it looks like this - the grammar point being aimed at, in Korean. */
    val note: String,
    val createdAt: Long,
    val writtenAt: Long? = null,
    val spokenAt: Long? = null,
    val speakingAccuracy: Float? = null,
) {
    val words: List<String>
        get() = targetWords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
