package com.englus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * What the question is testing.
 *
 * Split because the remedies differ: missing what was *said* is a perception
 * problem, missing what it *meant* is comprehension, and they should not be
 * averaged into one score.
 */
enum class ExamKind {
    /** Hear a clip, pick the sentence that was actually said. */
    HEARD,
    /** Hear a clip, answer a question about its content. */
    MEANING,
}

@Entity(tableName = "exam_sessions", indices = [Index("episodeId"), Index("createdAt")])
data class ExamSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val episodeTitle: String,
    val total: Int,
    val correct: Int = 0,
    val finished: Boolean = false,
    val elapsedMs: Long = 0,
    val createdAt: Long,
)

@Entity(
    tableName = "exam_questions",
    indices = [Index("sessionId")],
    foreignKeys = [
        ForeignKey(
            entity = ExamSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ExamQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val kind: ExamKind,
    val position: Int,
    /** Korean question text for MEANING; empty for HEARD, where the audio is the question. */
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    /** Audio range to play. Zero for reading material. */
    val startMs: Long,
    val endMs: Long,
    val chosenIndex: Int = -1,
) {
    val answered: Boolean get() = chosenIndex >= 0
    val isCorrect: Boolean get() = chosenIndex == correctIndex
}

class ExamConverters {
    @TypeConverter fun kindToString(k: ExamKind): String = k.name

    @TypeConverter
    fun stringToKind(s: String): ExamKind =
        runCatching { ExamKind.valueOf(s) }.getOrDefault(ExamKind.HEARD)

    /** JSON rather than a separator encoding: options are model-generated free text. */
    @TypeConverter
    fun optionsToString(options: List<String>): String =
        JSONArray().apply { options.forEach { put(it) } }.toString()

    @TypeConverter
    fun stringToOptions(raw: String): List<String> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
}

/** Accuracy by question type, which is the only breakdown worth acting on. */
data class ExamKindScore(val kind: ExamKind, val total: Int, val correct: Int)
