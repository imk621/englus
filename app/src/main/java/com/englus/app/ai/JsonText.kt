package com.englus.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured output for a model that does not enforce a schema.
 *
 * The cloud API takes responseSchema and guarantees the shape. Gemini Nano
 * takes only a prompt, so the shape has to be asked for in words and checked on
 * the way out. Both halves live here so they stay in step and can be tested on
 * the JVM.
 */
object JsonText {

    /**
     * An instruction that shows the exact shape to produce.
     *
     * A filled-in skeleton works better on a small model than the schema
     * itself: it copies the shape rather than interpreting a JSON-Schema
     * vocabulary it may not know.
     */
    fun instruction(schema: JSONObject): String = buildString {
        appendLine("출력 형식: 아래 모양의 JSON 객체 하나만 출력하라.")
        appendLine("코드 블록 표시(```), 설명, 머리말을 붙이지 마라. 모든 키를 빠짐없이 채워라.")
        append(skeleton(schema).toString())
    }

    /** A sample value of the schema's shape, with placeholder leaves. */
    fun skeleton(schema: JSONObject): Any = when (schema.optString("type").uppercase()) {
        "OBJECT" -> {
            val out = JSONObject()
            val props = schema.optJSONObject("properties") ?: JSONObject()
            props.keys().forEach { key -> out.put(key, skeleton(props.getJSONObject(key))) }
            out
        }
        "ARRAY" -> JSONArray().put(skeleton(schema.optJSONObject("items") ?: JSONObject()))
        "INTEGER", "NUMBER" -> 0
        "BOOLEAN" -> false
        else -> "..."
    }

    /**
     * The JSON object inside a model reply, or null.
     *
     * Small models wrap JSON in ```json fences or add a sentence before it
     * despite being told not to. Taking the outermost braces recovers both
     * cases; anything that still does not parse is treated as a failure, so the
     * caller can fall back rather than store half an answer.
     */
    fun extract(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
    }

    /** True when every key the schema marks required is present, recursively for objects. */
    fun satisfies(value: JSONObject, schema: JSONObject): Boolean {
        val required = schema.optJSONArray("required") ?: JSONArray()
        val props = schema.optJSONObject("properties") ?: JSONObject()
        for (i in 0 until required.length()) {
            val key = required.getString(i)
            if (!value.has(key) || value.isNull(key)) return false
            val sub = props.optJSONObject(key) ?: continue
            if (sub.optString("type").uppercase() == "OBJECT") {
                val child = value.optJSONObject(key) ?: return false
                if (!satisfies(child, sub)) return false
            }
            if (sub.optString("type").uppercase() == "ARRAY" && value.optJSONArray(key) == null) {
                return false
            }
        }
        return true
    }
}
