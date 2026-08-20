package com.englus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One shadowing attempt.
 *
 * Stored for the same reason dictation attempts are: a single score says nothing,
 * and "your accuracy on this episode went from 60% to 85%" is the only feedback
 * that tells a learner the practice is working.
 */
@Entity(
    tableName = "speaking_attempts",
    indices = [Index("sentenceId"), Index("attemptedAt")],
    foreignKeys = [
        ForeignKey(
            entity = Sentence::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class SpeakingAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sentenceId: Long,
    val episodeId: Long,
    /** Word-level agreement between the target sentence and what was heard back. */
    val accuracy: Float,
    val recognizedText: String,
    val targetText: String,
    val spokenMs: Long,
    val targetMs: Long,
    val attemptedAt: Long,
) {
    /**
     * How much slower or faster than the original. Rhythm is half of intelligible
     * speech and a word-accuracy score alone hides it completely.
     */
    val paceRatio: Float
        get() = if (targetMs <= 0) 1f else spokenMs.toFloat() / targetMs
}
