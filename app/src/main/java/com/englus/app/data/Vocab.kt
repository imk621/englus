package com.englus.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * What the learner's own record says about a word.
 *
 * Derived from evidence, not declared. A profile the learner has to fill in by
 * hand is a profile that stays empty, and self-report is the least reliable
 * source available — people are confident about words they cannot hear.
 */
enum class VocabState {
    /** Met and got wrong, with nothing on the other side of the ledger. */
    UNKNOWN,

    /** Mixed evidence, or seen too few times to call. */
    LEARNING,

    /** Repeatedly transcribed or spoken correctly. */
    KNOWN,
}

@Entity(tableName = "vocab_words", indices = [Index("rank"), Index("state")])
data class VocabWord(
    /** The lemma, so "studies" and "studied" are one entry. */
    @PrimaryKey val lemma: String,
    /** Frequency rank; 0 when the word is off the bundled list. */
    val rank: Int,
    val seen: Int,
    val correct: Int,
    val missed: Int,
    val state: VocabState,
    /**
     * A hand judgement overrides the evidence: 0 none, 1 "I know this",
     * 2 "I don't". Kept separate from [state] so that later evidence cannot
     * silently undo what the learner said.
     */
    val manual: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/** Known/unknown counts inside one frequency band. */
data class VocabBandStat(
    /** 0..4 for the K-bands in order, 5 for off-list. */
    val band: Int,
    val known: Int,
    val unknown: Int,
    val met: Int,
)

class VocabConverters {
    @TypeConverter fun toString(s: VocabState): String = s.name

    @TypeConverter
    fun fromString(s: String): VocabState =
        runCatching { VocabState.valueOf(s) }.getOrDefault(VocabState.LEARNING)
}
