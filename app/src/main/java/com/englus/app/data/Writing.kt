package com.englus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * The kinds of mistake worth separating for a Korean learner of English.
 *
 * Coarse on purpose. A taxonomy fine enough to be linguistically satisfying
 * produces counts of one per category and tells the learner nothing; these are
 * the buckets that actually accumulate and that have different remedies.
 */
enum class MistakeCategory {
    /** a / an / the, and the far harder case of no article at all. */
    ARTICLE,
    TENSE,
    PREPOSITION,
    /** Subject-verb and other agreement. */
    AGREEMENT,
    PLURAL,
    WORD_ORDER,
    /** Real word, wrong word. */
    VOCABULARY,
    /** Grammatical but not what a native speaker would say. */
    NATURALNESS,
    SPELLING,
    OTHER;

    companion object {
        fun of(raw: String?): MistakeCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OTHER
    }
}

@Entity(tableName = "writings", indices = [Index("createdAt")])
data class Writing(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Empty for free writing. */
    val prompt: String = "",
    val userText: String,
    /** Minimal correction: what the learner wrote, made correct. */
    val correctedText: String = "",
    /** What a native speaker would actually write — often quite different. */
    val naturalText: String = "",
    val overall: String = "",
    val model: String = "",
    val createdAt: Long,
)

@Entity(
    tableName = "writing_mistakes",
    indices = [Index("writingId"), Index("category")],
    foreignKeys = [
        ForeignKey(
            entity = Writing::class,
            parentColumns = ["id"],
            childColumns = ["writingId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class WritingMistake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val writingId: Long,
    val category: MistakeCategory,
    val original: String,
    val corrected: String,
    val explanation: String,
)

class WritingConverters {
    @TypeConverter fun toString(c: MistakeCategory): String = c.name

    @TypeConverter fun fromString(s: String): MistakeCategory = MistakeCategory.of(s)
}

/** One row of the writing weakness report. */
data class MistakeCount(val category: MistakeCategory, val count: Int)
