package com.englus.app.gemini

import android.content.Context
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.ExamKind
import com.englus.app.data.ExamQuestion
import com.englus.app.data.ExamSession
import com.englus.app.data.Sentence
import com.englus.app.feed.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates a listening exam from an episode the learner already has.
 *
 * Not a question bank: the material is the audio they chose, so a wrong answer
 * points at a clip they can replay rather than at a topic they will never meet
 * again. Distractors have to be *plausible mishearings* for that to work, which
 * is the one thing a generic quiz generator will not do.
 */
class ExamRepository(context: Context) {

    private val dao = EnglusDatabase.get(context).exams()
    private val episodes = EnglusDatabase.get(context).episodes()
    private val settings = GeminiSettings(context)

    val configured: Boolean get() = settings.configured

    fun observeQuestions(sessionId: Long) = dao.observeQuestions(sessionId)
    fun observeHistory() = dao.observeHistory()
    fun observeKindScores() = dao.observeKindScores()

    suspend fun session(id: Long) = dao.session(id)

    class ExamException(message: String) : Exception(message)

    suspend fun generate(episodeId: Long, count: Int = 8): Long = withContext(Dispatchers.IO) {
        val key = settings.apiKey.ifBlank { throw ExamException("Gemini API 키가 필요합니다.") }
        val episode = episodes.get(episodeId) ?: throw ExamException("에피소드를 찾을 수 없습니다.")

        // first(), not first { isNotEmpty() }: Room emits the current contents
        // immediately, and waiting for a non-empty list would hang forever on an
        // episode that genuinely has none.
        val all = episodes.observeSentences(episodeId).first()
        // Long sentences carry enough content to ask about; one-word segments and
        // [MUSIC] markers do not.
        val usable = all.filter { it.text.split(" ").size >= 5 }
        if (usable.size < 3) throw ExamException("문제를 만들 만한 문장이 부족합니다.")

        val picked = pickSpread(usable, count)
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", buildPrompt(picked))
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("responseSchema", SCHEMA)
                put("temperature", 0.4)
            })
        }.toString()

        val raw = Http.postJson("$BASE/models/${settings.model}:generateContent?key=$key", payload)
        val generated = parse(raw)
        if (generated.isEmpty()) throw ExamException("문제를 생성하지 못했습니다.")

        val sessionId = dao.insertSession(
            ExamSession(
                episodeId = episodeId,
                episodeTitle = episode.title,
                total = generated.size,
                createdAt = System.currentTimeMillis(),
            )
        )
        dao.insertQuestions(
            generated.mapIndexed { i, g ->
                val sentence = picked.getOrNull(g.sentenceIndex) ?: picked[i % picked.size]
                ExamQuestion(
                    sessionId = sessionId,
                    kind = g.kind,
                    position = i,
                    prompt = g.prompt,
                    options = g.options,
                    correctIndex = g.correctIndex,
                    explanation = g.explanation,
                    startMs = sentence.startMs,
                    endMs = sentence.endMs,
                )
            }
        )
        sessionId
    }

    /** Spread across the episode rather than clustering at the start. */
    private fun pickSpread(sentences: List<Sentence>, count: Int): List<Sentence> {
        if (sentences.size <= count) return sentences
        val step = sentences.size.toFloat() / count
        return (0 until count).map { sentences[(it * step).toInt().coerceAtMost(sentences.lastIndex)] }
    }

    suspend fun answer(question: ExamQuestion, chosen: Int) = withContext(Dispatchers.IO) {
        dao.updateQuestion(question.copy(chosenIndex = chosen))
    }

    suspend fun finish(sessionId: Long, correct: Int, elapsedMs: Long) = withContext(Dispatchers.IO) {
        val s = dao.session(sessionId) ?: return@withContext
        dao.updateSession(s.copy(finished = true, correct = correct, elapsedMs = elapsedMs))
    }

    private fun buildPrompt(sentences: List<Sentence>): String = buildString {
        appendLine("한국인 영어 학습자를 위한 듣기 문제를 만들어라. 4지선다다.")
        appendLine()
        appendLine("아래는 학습자가 듣고 있는 팟캐스트의 문장들이다. 각 문장에 번호가 붙어 있다.")
        appendLine("문장마다 문제를 하나씩 만들되, 종류를 섞어라:")
        appendLine("- HEARD: 실제로 무엇이라고 말했는지 고르는 문제. options 는 영어 문장 4개.")
        appendLine("  정답은 원문 그대로. 나머지 셋은 **실제로 잘못 들릴 법한** 문장이어야 한다 —")
        appendLine("  축약·연음·약형 때문에 헷갈리는 형태로 만들어라. 무관한 문장은 문제가 되지 않는다.")
        appendLine("- MEANING: 그 문장의 내용을 묻는 문제. prompt 는 한국어 질문, options 는 한국어 4개.")
        appendLine()
        appendLine("규칙:")
        appendLine("- sentenceIndex 는 아래 번호를 그대로 쓴다.")
        appendLine("- correctIndex 는 0부터 시작한다.")
        appendLine("- explanation 은 왜 그 답인지, 그리고 오답이 왜 헷갈리는지 한국어로 한두 문장.")
        appendLine("- HEARD 문제의 prompt 는 빈 문자열로 둬라. 오디오 자체가 질문이다.")
        appendLine()
        sentences.forEachIndexed { i, s -> appendLine("[$i] ${s.text}") }
    }

    private data class Generated(
        val sentenceIndex: Int,
        val kind: ExamKind,
        val prompt: String,
        val options: List<String>,
        val correctIndex: Int,
        val explanation: String,
    )

    private fun parse(raw: String): List<Generated> {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.let {
            throw ExamException(it.optString("message", "알 수 없는 오류"))
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw ExamException("응답 본문을 찾을 수 없습니다.")
        val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }

        val arr = runCatching { JSONObject(text).optJSONArray("questions") }.getOrNull()
            ?: throw ExamException("문제 형식을 해석할 수 없습니다.")

        return (0 until arr.length()).mapNotNull { i ->
            val q = arr.getJSONObject(i)
            val optionsArr = q.optJSONArray("options") ?: return@mapNotNull null
            val options = (0 until optionsArr.length()).map { optionsArr.getString(it) }
            // Four options and a valid answer index, or the question is unusable.
            if (options.size != 4) return@mapNotNull null
            val correct = q.optInt("correctIndex", -1)
            if (correct !in 0..3) return@mapNotNull null

            Generated(
                sentenceIndex = q.optInt("sentenceIndex", i),
                kind = runCatching { ExamKind.valueOf(q.optString("kind")) }.getOrDefault(ExamKind.HEARD),
                prompt = q.optString("prompt"),
                options = options,
                correctIndex = correct,
                explanation = q.optString("explanation"),
            )
        }
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        val SCHEMA: JSONObject = JSONObject("""
            {
              "type": "OBJECT",
              "properties": {
                "questions": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "sentenceIndex": {"type": "INTEGER"},
                      "kind": {"type": "STRING", "enum": ["HEARD", "MEANING"]},
                      "prompt": {"type": "STRING"},
                      "options": {"type": "ARRAY", "items": {"type": "STRING"}},
                      "correctIndex": {"type": "INTEGER"},
                      "explanation": {"type": "STRING"}
                    },
                    "required": ["sentenceIndex", "kind", "prompt", "options", "correctIndex", "explanation"]
                  }
                }
              },
              "required": ["questions"]
            }
        """.trimIndent())
    }
}
