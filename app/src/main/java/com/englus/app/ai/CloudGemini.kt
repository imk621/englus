package com.englus.app.ai

import com.englus.app.feed.Http
import com.englus.app.gemini.GeminiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Gemini REST API with the user's own key (BYOK).
 *
 * Five call sites used to build this payload and unwrap `candidates` each on
 * their own; they now share this one.
 */
class CloudGemini(private val settings: GeminiSettings) : TextModel {

    override val modelId: String get() = settings.model
    override val onDevice = false

    val configured: Boolean get() = settings.configured

    override suspend fun generate(request: TextRequest): String = withContext(Dispatchers.IO) {
        val key = settings.apiKey.ifBlank { throw AiException("Gemini API 키가 필요합니다.") }

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.prompt)))
            ))
            put("generationConfig", JSONObject().apply {
                request.schema?.let {
                    put("responseMimeType", "application/json")
                    put("responseSchema", it)
                }
                put("temperature", request.temperature.toDouble())
            })
        }.toString()

        val raw = Http.postJson("$BASE/models/${settings.model}:generateContent?key=$key", payload)
        textOf(raw)
    }

    /** Model ids change every few weeks; ask the API rather than guessing. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val key = settings.apiKey.ifBlank { throw AiException("Gemini API 키가 필요합니다.") }
        val body = Http.getText("$BASE/models?key=$key")
        val models = JSONObject(body).optJSONArray("models") ?: JSONArray()

        (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val supported = (0 until (methods?.length() ?: 0))
                .any { methods!!.getString(it) == "generateContent" }
            if (!supported) return@mapNotNull null
            m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }
        }.sorted()
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        fun textOf(raw: String): String {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.let {
                throw AiException(it.optString("message", "알 수 없는 오류"))
            }
            val candidates = root.optJSONArray("candidates")
                ?: throw AiException("응답에 candidates 가 없습니다: ${raw.take(200)}")
            if (candidates.length() == 0) throw AiException("모델이 빈 응답을 반환했습니다.")
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw AiException("응답 본문을 찾을 수 없습니다.")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        }
    }
}
