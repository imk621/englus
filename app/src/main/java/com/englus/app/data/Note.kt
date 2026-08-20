package com.englus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * A cloud explanation for one sentence, cached forever.
 *
 * Cached rather than fetched on every view because it costs an API call and a
 * round trip, and the answer for a fixed sentence does not change. Only
 * sentences the learner explicitly asked about ever get a row.
 */
@Entity(
    tableName = "sentence_notes",
    indices = [Index(value = ["sentenceId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = Sentence::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class SentenceNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sentenceId: Long,
    val translation: String,
    val structure: String,
    /** JSON array of {text, meaning}; free-form strings make a separator encoding unsafe here. */
    val expressions: String,
    val grammar: String,
    /** How the sentence actually sounds at speed — the part a dictionary cannot give. */
    val sound: String,
    /** Which model produced it, so a stale note can be identified after a model change. */
    val model: String,
    val createdAt: Long,
) {
    fun expressionList(): List<Pair<String, String>> = runCatching {
        val arr = JSONArray(expressions)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.optString("text") to o.optString("meaning")
        }
    }.getOrDefault(emptyList())

    companion object {
        fun encodeExpressions(pairs: List<Pair<String, String>>): String =
            JSONArray().apply {
                pairs.forEach { (t, m) ->
                    put(JSONObject().put("text", t).put("meaning", m))
                }
            }.toString()
    }
}
