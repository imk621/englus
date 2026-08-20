package com.englus.app.gemini

import android.content.Context
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Writing
import com.englus.app.data.WritingMistake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WritingRepository(context: Context) {

    private val dao = EnglusDatabase.get(context).writings()
    private val settings = GeminiSettings(context)
    private val client = WritingClient(settings)

    val configured: Boolean get() = settings.configured

    fun observeAll() = dao.observeAll()
    fun observeCount() = dao.observeCount()
    fun observeMistakeCounts() = dao.observeMistakeCounts()
    fun observeMistakes(writingId: Long) = dao.observeMistakes(writingId)

    suspend fun suggestPrompt(): String = client.suggestPrompt(dao.recentPrompts())

    /**
     * Corrects and stores in one step.
     *
     * The correction is saved even when the learner never looks at it again: the
     * mistake categories are the only thing that turns scattered corrections into
     * "you keep dropping articles", and that pattern needs history.
     */
    suspend fun correct(prompt: String, text: String): Long = withContext(Dispatchers.IO) {
        val correction = client.correct(prompt, text)

        val id = dao.insert(
            Writing(
                prompt = prompt,
                userText = text,
                correctedText = correction.corrected,
                naturalText = correction.natural,
                overall = correction.overall,
                model = settings.model,
                createdAt = System.currentTimeMillis(),
            )
        )
        dao.insertMistakes(
            correction.mistakes.map {
                WritingMistake(
                    writingId = id,
                    category = it.category,
                    original = it.original,
                    corrected = it.corrected,
                    explanation = it.explanation,
                )
            }
        )
        id
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
