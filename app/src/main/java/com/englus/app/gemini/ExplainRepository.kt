package com.englus.app.gemini

import android.content.Context
import com.englus.app.data.Diagnosis
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Sentence
import com.englus.app.data.SentenceNote
import kotlinx.coroutines.flow.Flow

class ExplainRepository(context: Context) {

    private val notes = EnglusDatabase.get(context).notes()
    private val episodes = EnglusDatabase.get(context).episodes()

    val settings = GeminiSettings(context)
    private val client = GeminiClient(settings)

    val configured: Boolean get() = settings.configured

    fun observeNote(sentenceId: Long): Flow<SentenceNote?> = notes.observe(sentenceId)

    suspend fun listModels(): List<String> = client.listModels()

    /**
     * Fetches and caches the explanation. Neighbouring lines go along as context:
     * without them a pronoun or a fragment gets explained in a vacuum, and
     * podcast speech is full of both.
     */
    suspend fun explain(sentence: Sentence, diagnosis: Diagnosis?): SentenceNote {
        val before = episodes.sentenceTextAt(sentence.episodeId, sentence.position - 1)
        val after = episodes.sentenceTextAt(sentence.episodeId, sentence.position + 1)

        val e = client.explain(
            target = sentence.text,
            before = before,
            after = after,
            diagnosis = diagnosis,
        )

        val note = SentenceNote(
            sentenceId = sentence.id,
            translation = e.translation,
            structure = e.structure,
            expressions = SentenceNote.encodeExpressions(e.expressions.map { it.text to it.meaning }),
            grammar = e.grammar,
            sound = e.sound,
            model = settings.model,
            createdAt = System.currentTimeMillis(),
        )
        notes.upsert(note)
        return note
    }

    suspend fun clear(sentenceId: Long) = notes.delete(sentenceId)
}
