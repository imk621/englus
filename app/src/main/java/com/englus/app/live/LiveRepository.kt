package com.englus.app.live

import android.content.Context
import com.englus.app.data.Conversation
import com.englus.app.data.ConversationTurn
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.TranscriptState
import com.englus.app.data.VocabState
import com.englus.app.feed.Http
import com.englus.app.gemini.GeminiSettings
import com.englus.app.level.LevelStore
import com.englus.app.vocab.VocabRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One exchange as it is being spoken, before it is written to the database. */
data class LiveTurn(val fromUser: Boolean, val text: String)

/**
 * A conversation starter.
 *
 * The label and the instruction are separate because they are read by different
 * audiences. The learner sees a Korean phrase or an episode title; the model
 * gets an English sentence telling it what to actually do with that. Feeding the
 * model a Korean label produced a partner that opened in Korean.
 */
data class Topic(val label: String, val prompt: String)

class LiveRepository(private val context: Context) {

    private val db = EnglusDatabase.get(context)
    private val dao = db.conversations()
    private val episodes = db.episodes()
    private val vocabDao = db.vocab()
    private val settings = GeminiSettings(context)
    private val vocab = VocabRepository(context)

    val budget = LiveBudget(context)

    val configured: Boolean get() = settings.configured
    val liveModel: String get() = settings.liveModel

    fun observeAll() = dao.observeAll()
    fun observeTurns(id: Long) = dao.observeTurns(id)
    fun observe(id: Long) = dao.observe(id)

    class LiveException(message: String) : Exception(message)

    fun apiKey(): String =
        settings.apiKey.ifBlank { throw LiveException("Gemini API 키가 필요합니다.") }

    /**
     * Topics drawn from what the learner has actually been listening to.
     *
     * A generic list of conversation starters produces a generic conversation.
     * Talking about the episode they heard this morning reuses the vocabulary
     * they just met, which is the only way a ten-minute call moves anything.
     */
    suspend fun topicSuggestions(): List<Topic> = withContext(Dispatchers.IO) {
        val recent = dao.recentTopics().toSet()
        val fromLibrary = episodes.observeAll().first()
            .filter { it.state == TranscriptState.DONE }
            // A title with no space in it is a file name, not a subject. Handing
            // "e2e-jfk" to the model as a topic produces exactly the conversation
            // that deserves.
            .filter { it.title.contains(" ") }
            .take(5)
            .map { episode ->
                val label = trimToWord(episode.title, 60)
                Topic(
                    label = label,
                    prompt = "something the learner has been listening to: \"$label\". " +
                        "Open by asking what they made of it, then follow where they go.",
                )
            }
        (fromLibrary + FALLBACK_TOPICS).filter { it.label !in recent }.distinct().take(8)
    }

    /** Cuts at a word boundary; a title chopped mid-word reads as a bug. */
    private fun trimToWord(text: String, max: Int): String {
        val clean = text.trim()
        if (clean.length <= max) return clean
        val cut = clean.take(max)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > max / 2) cut.take(lastSpace) else cut).trimEnd() + "..."
    }

    /**
     * The instruction that decides whether this is a lesson or a chatbot.
     *
     * Four things in here are not decoration. The model is told to keep its own
     * turns short, because the default behaviour of a capable assistant is to
     * fill the silence, and the learner is the one who needs the practice.
     * It is told not to correct mid-conversation, because being interrupted for
     * a missing article is how people stop speaking altogether; corrections are
     * collected afterwards from the transcript instead. It is told to stay in
     * English apart from a one-line rescue, since switching to Korean at the
     * first difficulty removes the difficulty that is the whole exercise. And it
     * is given the learner's real weak words, so the conversation walks toward
     * them rather than around them.
     */
    suspend fun systemInstruction(topic: Topic): String = withContext(Dispatchers.IO) {
        val level = level()
        val weak = vocabDao.observeUnknown(12).first()
            .filter { it.state == VocabState.UNKNOWN && it.rank in 1..4000 }
            .joinToString(", ") { it.lemma }

        buildString {
            appendLine("You are a friendly English conversation partner for a Korean learner.")
            appendLine("Their level is roughly $level. Speak naturally but at a measured pace,")
            appendLine("and stay within vocabulary a $level learner can follow.")
            appendLine()
            appendLine("Start the conversation from ${topic.prompt}")
            appendLine()
            appendLine("Rules:")
            appendLine("- Keep your turns to two or three sentences, then ask a question.")
            appendLine("  The learner should be speaking most of the time, not you.")
            appendLine("- Do NOT correct their grammar or vocabulary during the conversation.")
            appendLine("  Interrupting a learner to fix an article is how people stop talking.")
            appendLine("  Just keep the conversation going; corrections happen afterwards.")
            appendLine("- If you genuinely cannot understand them, ask them to say it again")
            appendLine("  in a natural way, as a person would.")
            appendLine("- Stay in English. If they are completely stuck, give one short Korean")
            appendLine("  hint and return to English immediately.")
            appendLine("- Never read out a list or lecture. This is a conversation.")
            if (weak.isNotBlank()) {
                appendLine()
                appendLine("These are words this learner has recently failed to catch:")
                appendLine("  $weak")
                appendLine("Work a few of them into your own turns naturally, so they hear them")
                appendLine("in context. Do not announce that you are doing this, and do not")
                appendLine("turn it into a vocabulary quiz.")
            }
        }
    }

    /**
     * The measured level if there is one, otherwise the profile, otherwise mid.
     *
     * The test wins because it samples the whole frequency range deliberately,
     * while the profile only ever sees words that happened to appear in the
     * material the learner worked through.
     */
    private suspend fun level(): String {
        LevelStore(context).cefr?.let { return it.label }
        val estimate = VocabRepository.estimateSize(vocabDao.observeBandStats().first())
            ?: return "B1"
        return when {
            estimate < 1500 -> "A2"
            estimate < 2500 -> "B1"
            estimate < 4000 -> "B2"
            else -> "C1"
        }
    }

    suspend fun save(topic: String, turns: List<LiveTurn>, durationSec: Int): Long =
        withContext(Dispatchers.IO) {
            val id = dao.insert(
                Conversation(
                    topic = topic,
                    startedAt = System.currentTimeMillis(),
                    durationSec = durationSec,
                    model = settings.liveModel,
                )
            )
            dao.insertTurns(
                turns.mapIndexed { i, t ->
                    ConversationTurn(
                        conversationId = id,
                        position = i,
                        fromUser = t.fromUser,
                        text = t.text,
                    )
                }
            )
            id
        }

    /**
     * Corrections, after the call rather than during it.
     *
     * Two reasons this is a separate text request and not part of the live
     * session. The live model is billed by the second in both directions, so
     * analysis belongs on the cheap path. And a model asked to teach while it
     * talks does both worse - it starts hedging every sentence into a lesson.
     */
    suspend fun review(conversationId: Long) = withContext(Dispatchers.IO) {
        val key = apiKey()
        val conversation = dao.get(conversationId) ?: throw LiveException("대화를 찾을 수 없습니다.")
        val turns = dao.turns(conversationId)
        val spoken = turns.filter { it.fromUser && it.text.isNotBlank() }
        if (spoken.isEmpty()) throw LiveException("첨삭할 발화가 없습니다.")

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", reviewPrompt(turns)))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", REVIEW_SCHEMA)
                    .put("temperature", 0.3)
            )
        }.toString()

        val raw = Http.postJson(
            "$BASE/models/${settings.model}:generateContent?key=$key",
            payload,
        )
        val parsed = parseReview(raw)

        var mistakes = 0
        parsed.corrections.forEach { c ->
            val turn = turns.getOrNull(c.position) ?: return@forEach
            if (!turn.fromUser) return@forEach
            mistakes++
            dao.updateTurn(turn.copy(correction = c.corrected, note = c.note))
        }
        dao.update(conversation.copy(feedback = parsed.overall, mistakeCount = mistakes))
    }

    private fun reviewPrompt(turns: List<ConversationTurn>): String = buildString {
        appendLine("아래는 한국인 영어 학습자와 영어 원어민 대화 상대의 실제 음성 대화 기록이다.")
        appendLine("학습자(USER)의 발화만 첨삭하라.")
        appendLine()
        appendLine("규칙:")
        appendLine("- position 은 아래 번호를 그대로 쓴다. USER 줄만 대상이다.")
        appendLine("- 고칠 것이 없는 문장은 목록에 넣지 마라. 멀쩡한 문장을 다르게 바꾸는 것은")
        appendLine("  첨삭이 아니라 취향이다.")
        appendLine("- 음성 인식 오류로 보이는 것은 건드리지 마라. 학습자가 실제로 잘못 말한 것만")
        appendLine("  다룬다. 발음 때문에 잘못 적힌 것과 문법이 틀린 것은 다르다.")
        appendLine("- corrected 는 자연스러운 원어민 표현으로, 원래 의도를 유지한 채 고쳐라.")
        appendLine("- note 는 왜 그렇게 고쳤는지 한국어로 한 문장.")
        appendLine("- overall 은 이 대화 전체에서 반복된 문제 한두 가지를 한국어로 짚어라.")
        appendLine("  잘한 점을 억지로 붙이지 마라.")
        appendLine()
        turns.forEachIndexed { i, t ->
            appendLine("[$i] ${if (t.fromUser) "USER" else "PARTNER"}: ${t.text}")
        }
    }

    private data class Correction(val position: Int, val corrected: String, val note: String)

    private data class Review(val overall: String, val corrections: List<Correction>)

    private fun parseReview(raw: String): Review {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.let {
            throw LiveException(it.optString("message", "알 수 없는 오류"))
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw LiveException("응답 본문을 찾을 수 없습니다.")
        val text = (0 until parts.length()).joinToString("") {
            parts.getJSONObject(it).optString("text")
        }
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: throw LiveException("첨삭 결과를 해석할 수 없습니다.")

        val arr = obj.optJSONArray("corrections") ?: JSONArray()
        return Review(
            overall = obj.optString("overall"),
            corrections = (0 until arr.length()).mapNotNull { i ->
                val c = arr.getJSONObject(i)
                val corrected = c.optString("corrected").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Correction(c.optInt("position", -1), corrected, c.optString("note"))
            }.filter { it.position >= 0 },
        )
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        val FALLBACK_TOPICS = listOf(
            Topic("오늘 하루 있었던 일", "how their day went. Ask for specifics, not a summary."),
            Topic("최근에 본 영화나 드라마", "a film or series they watched recently, and why it stayed with them."),
            Topic("주말 계획", "what they are planning for the weekend."),
            Topic("요즘 관심 있는 것", "something they have been interested in lately."),
        )

        val REVIEW_SCHEMA: JSONObject = JSONObject(
            """
            {
              "type": "OBJECT",
              "properties": {
                "overall": {"type": "STRING"},
                "corrections": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "position": {"type": "INTEGER"},
                      "corrected": {"type": "STRING"},
                      "note": {"type": "STRING"}
                    },
                    "required": ["position", "corrected", "note"]
                  }
                }
              },
              "required": ["overall", "corrections"]
            }
            """.trimIndent()
        )
    }
}
