package com.englus.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.englus.app.srs.CardState

/**
 * Scheduling state for one saved sentence.
 *
 * Separate from [Sentence] rather than more columns on it: a sentence is
 * content, a card is a study object with its own lifecycle, and the review log
 * has to reference something stable.
 */
@Entity(
    tableName = "review_cards",
    indices = [Index(value = ["sentenceId"], unique = true), Index("due")],
    foreignKeys = [
        ForeignKey(
            entity = Sentence::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ReviewCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sentenceId: Long,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val state: CardState = CardState.NEW,
    /** When this card should next be shown. A new card is due immediately. */
    val due: Long,
    val lastReview: Long = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val createdAt: Long,
)

/**
 * Every grading, kept forever.
 *
 * The published FSRS weights are population averages; re-fitting them to one
 * learner needs their own history, and history cannot be reconstructed after the
 * fact. Recording is cheap, so it starts on day one.
 */
@Entity(
    tableName = "review_logs",
    indices = [Index("cardId"), Index("reviewedAt")],
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val rating: Int,
    val stateBefore: CardState,
    val elapsedDays: Double,
    val scheduledDays: Int,
    val reviewedAt: Long,
)

class ReviewConverters {
    @TypeConverter fun toString(s: CardState): String = s.name

    @TypeConverter
    fun fromString(s: String): CardState =
        runCatching { CardState.valueOf(s) }.getOrDefault(CardState.NEW)
}

/** A due card with everything the session needs to play and show it. */
data class DueCard(
    @Embedded val card: ReviewCard,
    val text: String,
    val translation: String?,
    val startMs: Long,
    val endMs: Long,
    val episodeTitle: String,
    val audioPath: String,
    /** Why it was missed, when it came from dictation. */
    val lastDiagnosis: String?,
) {
    val diagnosis: Diagnosis?
        get() = lastDiagnosis?.let { runCatching { Diagnosis.valueOf(it) }.getOrNull() }
}
