package com.englus.app.ai

import org.json.JSONObject

/**
 * One request to a text model.
 *
 * [schema] is the Gemini REST responseSchema dialect (OBJECT / ARRAY / STRING …).
 * The cloud model enforces it; the on-device model cannot, so it is turned into
 * an instruction plus a validity check there (see [JsonText]).
 */
data class TextRequest(
    val prompt: String,
    val schema: JSONObject? = null,
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 1024,
)

/** What came back, and from where: the model id is stored with saved results. */
data class TextResult(val text: String, val modelId: String, val onDevice: Boolean)

/** A text model that turns a prompt into text, or JSON text when a schema is given. */
interface TextModel {
    val modelId: String
    val onDevice: Boolean
    suspend fun generate(request: TextRequest): String
}

/** Carries a user-facing Korean message; screens show [message] as is. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The jobs the app gives a text model.
 *
 * [onDeviceFirst] is a quality decision, not a capability one. Gemini Nano can
 * produce all of these, but an exam needs distractors that are *plausible
 * mishearings* of a specific sentence. That is the hardest generation the app
 * asks for, and a weak distractor silently makes the exam measure nothing.
 * It stays on the cloud model unless there is no key. `tools/hosteval` is
 * where this split gets re-measured.
 */
enum class AiTask(val onDeviceFirst: Boolean) {
    EXPLAIN(true),
    WRITING_CORRECTION(true),
    WRITING_TOPIC(true),
    DAILY(true),
    EXAM(false),
}

/** The user's choice in settings. */
enum class AiEngine(val label: String) {
    AUTO("자동 (기기 우선, 안 되면 클라우드)"),
    ON_DEVICE("기기 안에서만 (Gemini Nano)"),
    CLOUD("클라우드만 (Gemini API)"),
}
