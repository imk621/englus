package com.englus.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Why a sentence was missed.
 *
 * This split is the reason the app exists. A learner who reads fluently but
 * cannot follow speech is usually failing on SOUND — connected speech, weak
 * forms, reductions — and drilling vocabulary for that is wasted effort. Most
 * apps score dictation and stop there, which tells you that you were wrong but
 * not what to practise.
 */
enum class Diagnosis {
    /** Transcribed correctly. */
    CORRECT,

    /** Knows the words on sight but could not hear them. */
    SOUND,

    /** Does not know the words at all. */
    VOCAB,

    /** Got it once the audio was slowed down — comprehension lagging, not missing. */
    SPEED,
}

@Entity(
    tableName = "dictation_attempts",
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
data class DictationAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sentenceId: Long,
    val episodeId: Long,
    val userText: String,
    val correctText: String,
    /** Word-level accuracy, 0..1. */
    val accuracy: Float,
    val diagnosis: Diagnosis,
    /** True when the answer came only after slowing the audio down. */
    val usedSlowReplay: Boolean,
    val attemptedAt: Long,
)

class DictationConverters {
    @TypeConverter fun toString(d: Diagnosis): String = d.name

    @TypeConverter
    fun fromString(s: String): Diagnosis =
        runCatching { Diagnosis.valueOf(s) }.getOrDefault(Diagnosis.SOUND)
}

/** A saved sentence with what the review screen needs to play and label it. */
data class SavedSentence(
    @Embedded val sentence: Sentence,
    val episodeTitle: String,
    val audioPath: String,
    /**
     * Raw column rather than a typed enum: the LEFT JOIN yields null for
     * sentences bookmarked by hand that were never dictated, and a nullable
     * converter is more machinery than one `?.let` is worth.
     */
    val lastDiagnosis: String?,
) {
    val diagnosis: Diagnosis?
        get() = lastDiagnosis?.let { runCatching { Diagnosis.valueOf(it) }.getOrNull() }
}

/** One row of the weakness report. */
data class DiagnosisCount(val diagnosis: Diagnosis, val count: Int)
