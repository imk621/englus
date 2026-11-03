package com.englus.app

import com.englus.app.ai.AiTask
import com.englus.app.ai.JsonText
import com.englus.app.data.Diagnosis
import com.englus.app.gemini.ExamRepository
import com.englus.app.gemini.GeminiClient
import com.englus.app.gemini.WritingClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Builds the exact on-device prompts for tools/hosteval/samples.json.
 *
 * The PC evaluation must replay what the phone would send, so it takes the
 * prompts from the app's own builders rather than a Python copy that would
 * drift on the next prompt edit. With HOSTEVAL_OUT set (tools/hosteval/run.sh
 * does it) the prompts are written there; otherwise this only checks that
 * every sample produces a prompt that carries the schema instruction.
 */
class HostEvalPromptsTest {

    @Test fun buildPrompts() {
        val samples = JSONObject(File("../tools/hosteval/samples.json").readText())
        val out = StringBuilder()

        fun emit(task: AiTask, id: String, prompt: String, schema: JSONObject) {
            val full = JsonText.onDevicePrompt(prompt, schema)
            assertTrue(id, full.contains("출력 형식"))
            out.append(
                JSONObject()
                    .put("task", task.name)
                    .put("id", id)
                    .put("onDeviceFirst", task.onDeviceFirst)
                    .put("prompt", full)
                    // The cloud path sends the bare prompt with the schema enforced.
                    .put("cloudPrompt", prompt)
                    .put("schema", schema)
                    .toString()
            ).append('\n')
        }

        samples.getJSONArray("explain").forEachObject { s ->
            emit(
                AiTask.EXPLAIN, s.getString("id"),
                GeminiClient.buildPrompt(
                    target = s.getString("target"),
                    before = s.optString("before").ifBlank { null },
                    after = s.optString("after").ifBlank { null },
                    diagnosis = s.optString("diagnosis").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { Diagnosis.valueOf(it) },
                ),
                GeminiClient.RESPONSE_SCHEMA,
            )
        }
        samples.getJSONArray("writing").forEachObject { s ->
            emit(
                AiTask.WRITING_CORRECTION, s.getString("id"),
                WritingClient.buildPrompt(s.getString("prompt"), s.getString("text")),
                WritingClient.CORRECTION_SCHEMA,
            )
        }
        samples.getJSONArray("exam").forEachObject { s ->
            val texts = s.getJSONArray("sentences").let { a -> (0 until a.length()).map { a.getString(it) } }
            emit(AiTask.EXAM, s.getString("id"), ExamRepository.buildPrompt(texts), ExamRepository.SCHEMA)
        }

        System.getenv("HOSTEVAL_OUT")?.let { path ->
            File(path).apply { parentFile?.mkdirs() }.writeText(out.toString())
        }
    }

    private fun JSONArray.forEachObject(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) f(getJSONObject(i))
    }
}
