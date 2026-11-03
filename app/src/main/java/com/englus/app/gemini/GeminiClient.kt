package com.englus.app.gemini

import com.englus.app.data.Diagnosis
import com.englus.app.ai.AiException
import com.englus.app.ai.AiRouter
import com.englus.app.ai.AiTask
import com.englus.app.ai.TextRequest
import org.json.JSONArray
import org.json.JSONObject

data class Expression(val text: String, val meaning: String)

data class Explanation(
    val translation: String,
    val structure: String,
    val expressions: List<Expression>,
    val grammar: String,
    val sound: String,
)

/**
 * The "why" layer above on-device translation.
 *
 * ML Kit gets the gist and loses sentence type — it renders "ask not what your
 * country can do for you" as a statement rather than an imperative. That gap is
 * exactly where a learner needs help, so it is worth a network call and an API
 * key, but only for sentences the learner asks about.
 */
class GeminiClient(private val router: AiRouter) {

    suspend fun explain(
        target: String,
        before: String?,
        after: String?,
        diagnosis: Diagnosis?,
    ): Pair<Explanation, String> {
        val result = router.generate(
            AiTask.EXPLAIN,
            TextRequest(
                prompt = buildPrompt(target, before, after, diagnosis),
                schema = RESPONSE_SCHEMA,
                temperature = 0.2f,
            ),
        )
        return parse(result.text) to result.modelId
    }

    private fun buildPrompt(
        target: String,
        before: String?,
        after: String?,
        diagnosis: Diagnosis?,
    ): String = buildString {
        appendLine("너는 한국인 영어 학습자를 가르치는 교사다. 아래 영어 문장 하나를 설명하라.")
        appendLine("모든 설명은 한국어로 쓴다. 문장은 실제 팟캐스트에서 발화된 것이다.")
        appendLine()
        appendLine("규칙:")
        appendLine("- translation 은 직역이 아니라 실제 의미다. 명령문, 수사의문문, 도치 같은")
        appendLine("  문장 유형을 반드시 반영하라. 기계번역이 이 부분을 자주 틀린다.")
        appendLine("- sound 는 이 문장이 자연스러운 속도로 발화될 때 어떻게 뭉개지는지 쓴다.")
        appendLine("  연음, 축약, 약해지는 기능어를 구체적으로 짚어라. 사전에 없는 정보다.")
        appendLine("- expressions 는 2~4개. 이 문장에서 실제로 배울 값이 있는 것만.")
        appendLine("- 앞뒤 문장은 문맥 파악용일 뿐, 설명 대상이 아니다.")
        diagnosis?.let {
            appendLine()
            appendLine("학습자는 이 문장을 받아쓰기에서 틀렸고, 진단은 다음과 같다:")
            appendLine(
                when (it) {
                    Diagnosis.SOUND ->
                        "- 읽으면 뜻을 알지만 듣지 못했다. sound 설명에 특히 공을 들여라."
                    Diagnosis.VOCAB ->
                        "- 단어 자체를 몰랐다. expressions 와 translation 에 특히 공을 들여라."
                    Diagnosis.SPEED ->
                        "- 느리게 들려주자 알아들었다. 어느 구간이 빠르게 뭉치는지 sound 에 짚어라."
                    Diagnosis.CORRECT -> "- 받아쓰기는 맞혔다. 더 깊은 이해를 돕는 설명을 하라."
                }
            )
        }
        appendLine()
        before?.takeIf { it.isNotBlank() }?.let { appendLine("[앞 문장] $it") }
        appendLine("[대상 문장] $target")
        after?.takeIf { it.isNotBlank() }?.let { appendLine("[뒤 문장] $it") }
    }

    private fun parse(text: String): Explanation {
        val o = runCatching { JSONObject(text) }
            .getOrElse { throw AiException("JSON 파싱 실패: ${text.take(200)}") }

        val exprArray = o.optJSONArray("expressions") ?: JSONArray()
        return Explanation(
            translation = o.optString("translation"),
            structure = o.optString("structure"),
            expressions = (0 until exprArray.length()).map { i ->
                val e = exprArray.getJSONObject(i)
                Expression(e.optString("text"), e.optString("meaning"))
            },
            grammar = o.optString("grammar"),
            sound = o.optString("sound"),
        )
    }

    private companion object {
        val RESPONSE_SCHEMA: JSONObject = JSONObject("""
            {
              "type": "OBJECT",
              "properties": {
                "translation": {"type": "STRING"},
                "structure":   {"type": "STRING"},
                "expressions": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "text":    {"type": "STRING"},
                      "meaning": {"type": "STRING"}
                    },
                    "required": ["text", "meaning"]
                  }
                },
                "grammar": {"type": "STRING"},
                "sound":   {"type": "STRING"}
              },
              "required": ["translation", "structure", "expressions", "grammar", "sound"]
            }
        """.trimIndent())
    }
}
