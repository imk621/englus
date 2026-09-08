package com.englus.app.daily

import android.content.Context
import com.englus.app.data.DailySentence
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.VocabState
import com.englus.app.feed.Http
import com.englus.app.gemini.GeminiSettings
import com.englus.app.level.LevelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class DailyRepository(private val context: Context) {

    private val db = EnglusDatabase.get(context)
    private val dao = db.daily()
    private val vocabDao = db.vocab()
    private val writingDao = db.writings()
    private val settings = GeminiSettings(context)

    val configured: Boolean get() = settings.configured

    class DailyException(message: String) : Exception(message)

    fun observeToday() = dao.observeForDate(todayKey())
    fun observeRecent() = dao.observeRecent()

    suspend fun get(id: Long) = withContext(Dispatchers.IO) { dao.get(id) }

    suspend fun hasToday(): Boolean =
        withContext(Dispatchers.IO) { dao.countForDate(todayKey()) > 0 }

    /**
     * Builds today's set, once.
     *
     * A day is generated in one request and reused by the writing task, the
     * speaking task and the home screen. On the free tier a text request costs
     * nothing, but one a day is also all this is worth: a second set would only
     * be more sentences nobody got to.
     */
    suspend fun generateToday(force: Boolean = false): List<DailySentence> =
        withContext(Dispatchers.IO) {
            val key = settings.apiKey.ifBlank { throw DailyException("Gemini API 키가 필요합니다.") }
            val dateKey = todayKey()
            if (!force) {
                val existing = dao.forDate(dateKey)
                if (existing.isNotEmpty()) return@withContext existing
            }

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", buildPrompt())
                    ))
                ))
                put("generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", SCHEMA)
                    // High enough that a week does not read like one template.
                    .put("temperature", 1.0))
            }.toString()

            val raw = Http.postJson("$BASE/models/${settings.model}:generateContent?key=$key", payload)
            val generated = parse(raw)
            if (generated.isEmpty()) throw DailyException("문장을 만들지 못했습니다.")

            val now = System.currentTimeMillis()
            dao.insert(
                generated.mapIndexed { i, g ->
                    DailySentence(
                        dateKey = dateKey,
                        position = i,
                        korean = g.korean,
                        english = g.english,
                        targetWords = g.words.joinToString(", "),
                        note = g.note,
                        createdAt = now,
                    )
                }
            )
            // A month of untouched sets is clutter; anything worked on is kept.
            dao.pruneUntouched(now - 30L * 24 * 3_600_000)
            dao.forDate(dateKey)
        }

    suspend fun markWritten(id: Long) = withContext(Dispatchers.IO) {
        dao.get(id)?.let { dao.update(it.copy(writtenAt = System.currentTimeMillis())) }
    }

    suspend fun markSpoken(id: Long, accuracy: Float) = withContext(Dispatchers.IO) {
        dao.get(id)?.let {
            dao.update(it.copy(spokenAt = System.currentTimeMillis(), speakingAccuracy = accuracy))
        }
    }

    /**
     * The prompt, built from what the app already knows about this learner.
     *
     * This is the whole reason to generate rather than take a sentence from a
     * book. A podcast will never decide to use the six words this person keeps
     * failing, and the generic writing topic the app offered before ignored
     * every measurement it had taken.
     */
    private suspend fun buildPrompt(): String {
        val weakWords = vocabDao.observeUnknown(30).first()
            .filter { it.state == VocabState.UNKNOWN && it.rank in 1..6000 }
            .take(8)
            .map { it.lemma }

        val mistakes = writingDao.observeMistakeCounts().first()
            .take(3)
            .map { "${it.category.name} ${it.count}회" }

        val level = LevelStore(context).cefr?.label ?: "B1"
        val recent = dao.recentEnglish(20)

        return buildString {
            appendLine("한국인 영어 학습자를 위한 오늘의 산출 연습 문장 ${COUNT}개를 만들어라.")
            appendLine("학습자 수준은 대략 $level 이다.")
            appendLine()
            appendLine("각 항목은 다음으로 이루어진다:")
            appendLine("- korean: 학습자가 영어로 옮길 한국어 문장. 한 문장.")
            appendLine("- english: 그 한국어를 원어민이 실제로 쓸 법하게 옮긴 영어 한 문장.")
            appendLine("- words: 이 문장에 일부러 넣은 목표 단어들.")
            appendLine("- note: 이 문장에서 조심할 지점을 한국어로 한 문장.")
            appendLine()

            if (weakWords.isNotEmpty()) {
                appendLine("목표 단어 (이 학습자가 실제로 놓친 단어들이다):")
                appendLine("  ${weakWords.joinToString(", ")}")
                appendLine("각 문장에 이 중 1~2개를 자연스럽게 넣어라. 억지로 끼워 넣지 마라.")
                appendLine("문장이 어색해지면 그 단어는 버리고 다른 것을 골라라.")
                appendLine()
            }
            if (mistakes.isNotEmpty()) {
                appendLine("이 학습자가 반복하는 오류 유형:")
                appendLine("  ${mistakes.joinToString(", ")}")
                appendLine("그 문법 요소가 반드시 필요한 문장으로 만들어라. 피해 가지 마라.")
                appendLine("설명하지 말고, 그 지점을 통과해야만 옳게 쓸 수 있는 문장이면 된다.")
                appendLine()
            }

            appendLine("규칙:")
            appendLine("- 영어는 교과서 문장이 아니라 사람이 실제로 말하는 문장이어야 한다.")
            appendLine("- 한국어 어순이 영어에 그대로 대응되면 안 된다. 기계적으로 바꿔 쓸 수")
            appendLine("  있는 문장은 번역 연습이 아니라 받아쓰기다.")
            appendLine("- 세 문장은 서로 다른 상황이어야 한다.")
            appendLine("- 지식이나 상식을 묻지 마라. 일상적인 말이면 된다.")

            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("최근에 낸 문장들이므로 겹치지 않게 하라:")
                recent.forEach { appendLine("  - $it") }
            }
        }
    }

    private data class Generated(
        val korean: String,
        val english: String,
        val words: List<String>,
        val note: String,
    )

    private fun parse(raw: String): List<Generated> {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.let {
            throw DailyException(it.optString("message", "알 수 없는 오류"))
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw DailyException("응답 본문을 찾을 수 없습니다.")
        val text = (0 until parts.length()).joinToString("") {
            parts.getJSONObject(it).optString("text")
        }
        val arr = runCatching { JSONObject(text).optJSONArray("sentences") }.getOrNull()
            ?: throw DailyException("문장 형식을 해석할 수 없습니다.")

        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val korean = o.optString("korean").trim()
            val english = o.optString("english").trim()
            if (korean.isBlank() || english.isBlank()) return@mapNotNull null
            val wordsArr = o.optJSONArray("words") ?: JSONArray()
            Generated(
                korean = korean,
                english = english,
                words = (0 until wordsArr.length()).map { wordsArr.getString(it) },
                note = o.optString("note").trim(),
            )
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        /** Three: one to write, one to say, and one spare for the home screen. */
        const val COUNT = 3

        fun todayKey(now: Long = System.currentTimeMillis()): Int {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            return cal.get(Calendar.YEAR) * 10000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
        }

        private val SCHEMA: JSONObject = JSONObject(
            """
            {
              "type": "OBJECT",
              "properties": {
                "sentences": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "korean": {"type": "STRING"},
                      "english": {"type": "STRING"},
                      "words": {"type": "ARRAY", "items": {"type": "STRING"}},
                      "note": {"type": "STRING"}
                    },
                    "required": ["korean", "english", "words", "note"]
                  }
                }
              },
              "required": ["sentences"]
            }
            """.trimIndent()
        )
    }
}
