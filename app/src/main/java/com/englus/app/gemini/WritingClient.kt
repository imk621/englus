package com.englus.app.gemini

import com.englus.app.data.MistakeCategory
import com.englus.app.ai.AiException
import com.englus.app.ai.AiRouter
import com.englus.app.ai.AiTask
import com.englus.app.ai.TextRequest
import org.json.JSONArray
import org.json.JSONObject

data class Correction(
    val corrected: String,
    val natural: String,
    val overall: String,
    val mistakes: List<CorrectionItem>,
    /** Which model wrote this; stored with the correction. */
    val model: String = "",
)

data class CorrectionItem(
    val category: MistakeCategory,
    val original: String,
    val corrected: String,
    val explanation: String,
)

/**
 * Writing correction.
 *
 * Two rewrites, not one. A minimally corrected version shows the learner exactly
 * what was wrong with what they wrote; a natural version shows what a native
 * speaker would have written instead. Collapsing them into one output loses
 * whichever half the learner needed — either the error is buried in a rewrite,
 * or the rewrite never happens and the sentence stays stiff but "correct".
 */
class WritingClient(private val router: AiRouter) {

    suspend fun correct(prompt: String, text: String): Correction {
        val result = router.generate(
            AiTask.WRITING_CORRECTION,
            TextRequest(buildPrompt(prompt, text), CORRECTION_SCHEMA, temperature = 0.2f),
        )
        return parseCorrection(result.text).copy(model = result.modelId)
    }

    /** A topic to write about, when the learner has nothing in mind. */
    suspend fun suggestPrompt(recentTopics: List<String>): String {
        val instruction = buildString {
            appendLine("한국인 영어 학습자를 위한 영작문 주제를 하나만 제안하라.")
            appendLine("- 5~8문장으로 답할 수 있는 크기여야 한다.")
            appendLine("- 개인 경험이나 의견을 묻는 주제가 좋다. 지식을 묻지 마라.")
            appendLine("- 한국어 한 문장으로 쓰고, 괄호 안에 영어 표현 힌트 2개를 덧붙여라.")
            appendLine("- 주제만 출력하라. 다른 말은 하지 마라.")
            if (recentTopics.isNotEmpty()) {
                appendLine()
                appendLine("최근에 쓴 주제이므로 피하라:")
                recentTopics.take(10).forEach { appendLine("- $it") }
            }
        }

        val result = router.generate(
            AiTask.WRITING_TOPIC,
            TextRequest(instruction, schema = null, temperature = 1.0f, maxOutputTokens = 200),
        )
        return result.text.trim().ifBlank { throw AiException("주제를 받지 못했습니다.") }
    }

    private fun buildPrompt(prompt: String, text: String): String = buildString {
        appendLine("너는 한국인 영어 학습자의 영작문을 첨삭하는 교사다.")
        appendLine("설명은 모두 한국어로, 교정문은 영어로 쓴다.")
        appendLine()
        appendLine("규칙:")
        appendLine("- corrected: 학습자가 쓴 문장을 최소한으로 고친 것. 문체를 바꾸지 말고")
        appendLine("  틀린 부분만 고쳐라. 무엇이 틀렸는지 보이는 것이 목적이다.")
        appendLine("- natural: 원어민이라면 실제로 이렇게 쓸 문장. corrected 와 달라도 좋다.")
        appendLine("- mistakes: 실제로 고친 것만. 취향 차이는 넣지 마라. category 는")
        appendLine("  ${MistakeCategory.entries.joinToString(", ") { it.name }} 중 하나.")
        appendLine("- explanation: 왜 틀렸는지 규칙을 한국어로 한두 문장. '어색하다'로 끝내지 마라.")
        appendLine("- 틀린 곳이 없으면 mistakes 를 빈 배열로 두고 corrected 는 원문 그대로 둬라.")
        appendLine("- overall: 잘한 점 하나와 다음에 신경 쓸 점 하나. 한국어 두세 문장.")
        appendLine()
        if (prompt.isNotBlank()) appendLine("[주제] $prompt")
        appendLine("[학습자가 쓴 글]")
        appendLine(text)
    }

    private fun parseCorrection(raw: String): Correction {
        val o = runCatching { JSONObject(raw) }
            .getOrElse { throw AiException("응답을 해석할 수 없습니다.") }

        val arr = o.optJSONArray("mistakes") ?: JSONArray()
        return Correction(
            corrected = o.optString("corrected"),
            natural = o.optString("natural"),
            overall = o.optString("overall"),
            mistakes = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                CorrectionItem(
                    category = MistakeCategory.of(m.optString("category")),
                    original = m.optString("original"),
                    corrected = m.optString("corrected"),
                    explanation = m.optString("explanation"),
                )
            },
        )
    }

    private companion object {
        val CORRECTION_SCHEMA: JSONObject = JSONObject("""
            {
              "type": "OBJECT",
              "properties": {
                "corrected": {"type": "STRING"},
                "natural":   {"type": "STRING"},
                "overall":   {"type": "STRING"},
                "mistakes": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "category":    {"type": "STRING"},
                      "original":    {"type": "STRING"},
                      "corrected":   {"type": "STRING"},
                      "explanation": {"type": "STRING"}
                    },
                    "required": ["category", "original", "corrected", "explanation"]
                  }
                }
              },
              "required": ["corrected", "natural", "overall", "mistakes"]
            }
        """.trimIndent())
    }
}
