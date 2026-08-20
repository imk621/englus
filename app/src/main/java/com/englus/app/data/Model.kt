package com.englus.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class TranscriptState { PENDING, RUNNING, DONE, FAILED }

enum class TranslationState { NONE, RUNNING, DONE, FAILED }

/**
 * Listening material has audio and word timings; reading material does not.
 * Everything else — sentences, translation, explanation, bookmarks, review —
 * is identical, which is why reading reuses this table instead of getting its
 * own parallel one.
 */
enum class EpisodeKind { LISTENING, READING }

@Entity(tableName = "episodes")
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Copied into app storage on import; SAF permissions can be revoked. */
    val audioPath: String,
    val durationMs: Long = 0,
    val importedAt: Long,
    val state: TranscriptState = TranscriptState.PENDING,
    val progress: Float = 0f,
    val error: String? = null,
    val modelName: String? = null,
    val transcribeMs: Long = 0,
    /**
     * Explicit SQL default: SQLite requires one to ADD a NOT NULL column, and
     * Room compares defaults when validating a migration.
     */
    @ColumnInfo(defaultValue = "NONE")
    val translationState: TranslationState = TranslationState.NONE,
    @ColumnInfo(defaultValue = "LISTENING")
    val kind: EpisodeKind = EpisodeKind.LISTENING,
)

/** One word with the timing that makes karaoke, seek-to-sentence and shadowing possible. */
data class Word(val text: String, val startMs: Long, val endMs: Long)

@Entity(
    tableName = "sentences",
    indices = [Index("episodeId"), Index("episodeId", "position")],
    foreignKeys = [
        ForeignKey(
            entity = Episode::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class Sentence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val position: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /**
     * Stored inline rather than in a table of its own: a 40-minute episode has
     * ~8,500 words but only ~500 sentences, and words are only ever read as a
     * whole sentence's worth. A row per word would buy nothing and cost a join
     * on every playback tick.
     */
    val words: List<Word>,
    /** Marked by the user as "I could not catch this" — the seed of the review deck. */
    val saved: Boolean = false,
    /**
     * Korean, filled in after transcription by [com.englus.app.translate.TranslateWorker].
     * Null until then, and null forever if translation failed — the sentence is
     * still fully usable for listening without it.
     */
    val translation: String? = null,
)

class Converters {

    /**
     * Compact separator encoding instead of JSON. Whisper tokens never contain
     * these control characters, and this avoids escaping plus a parser pass over
     * every sentence on load.
     */
    @TypeConverter
    fun wordsToString(words: List<Word>): String =
        words.joinToString(REC) { "${it.text}$UNIT${it.startMs}$UNIT${it.endMs}" }

    @TypeConverter
    fun stringToWords(raw: String): List<Word> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(REC).mapNotNull { entry ->
            val parts = entry.split(UNIT)
            if (parts.size != 3) return@mapNotNull null
            Word(parts[0], parts[1].toLongOrNull() ?: 0L, parts[2].toLongOrNull() ?: 0L)
        }
    }

    @TypeConverter fun stateToString(s: TranscriptState): String = s.name

    @TypeConverter fun kindToString(k: EpisodeKind): String = k.name

    @TypeConverter
    fun stringToKind(s: String): EpisodeKind =
        runCatching { EpisodeKind.valueOf(s) }.getOrDefault(EpisodeKind.LISTENING)

    @TypeConverter fun translationStateToString(s: TranslationState): String = s.name

    @TypeConverter
    fun stringToTranslationState(s: String): TranslationState =
        runCatching { TranslationState.valueOf(s) }.getOrDefault(TranslationState.NONE)

    @TypeConverter
    fun stringToState(s: String): TranscriptState =
        runCatching { TranscriptState.valueOf(s) }.getOrDefault(TranscriptState.FAILED)

    private companion object {
        // ASCII record/unit separators (0x1E / 0x1F). Built from char codes rather
        // than written as literals, so the source stays free of control characters
        // that editors and diffs mangle. The whisper tokenizer never emits them.
        val REC: String = 0x1E.toChar().toString()
        val UNIT: String = 0x1F.toChar().toString()
    }
}
