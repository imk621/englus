package com.englus.app

import com.englus.app.ai.JsonText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini Nano has no schema enforcement, so these two directions - asking for
 * a shape, and accepting only that shape - are the whole structured-output
 * contract on device. A regression here stores half an explanation instead of
 * falling back to the cloud.
 */
class JsonTextTest {

    private val schema = JSONObject("""
        {
          "type": "OBJECT",
          "properties": {
            "translation": {"type": "STRING"},
            "expressions": {
              "type": "ARRAY",
              "items": {
                "type": "OBJECT",
                "properties": {"text": {"type": "STRING"}, "meaning": {"type": "STRING"}},
                "required": ["text", "meaning"]
              }
            },
            "score": {"type": "INTEGER"}
          },
          "required": ["translation", "expressions"]
        }
    """)

    @Test fun skeletonMirrorsShape() {
        val s = JsonText.skeleton(schema) as JSONObject
        assertEquals("...", s.getString("translation"))
        assertEquals("...", s.getJSONArray("expressions").getJSONObject(0).getString("meaning"))
        assertEquals(0, s.getInt("score"))
    }

    @Test fun instructionCarriesEveryKey() {
        val text = JsonText.instruction(schema)
        listOf("translation", "expressions", "text", "meaning", "score").forEach {
            assertTrue(it, text.contains("\"$it\""))
        }
    }

    @Test fun extractsPlainObject() {
        assertNotNull(JsonText.extract("""{"translation":"a","expressions":[]}"""))
    }

    @Test fun extractsFromCodeFence() {
        val reply = "```json\n{\"translation\":\"안녕\",\"expressions\":[]}\n```"
        assertEquals("안녕", JsonText.extract(reply)!!.getString("translation"))
    }

    @Test fun extractsAfterPreamble() {
        val reply = "Here is the JSON:\n{\"translation\":\"x\",\"expressions\":[]}\nHope it helps."
        assertEquals("x", JsonText.extract(reply)!!.getString("translation"))
    }

    @Test fun rejectsTruncated() {
        // What a too-small output cap produces: the fallback must trigger.
        assertNull(JsonText.extract("""{"translation":"a","expressions":[{"text":"b","mea"""))
    }

    @Test fun rejectsNoObject() {
        assertNull(JsonText.extract("죄송하지만 도와드릴 수 없습니다."))
    }

    @Test fun satisfiesRequiresKeys() {
        assertTrue(JsonText.satisfies(JSONObject("""{"translation":"a","expressions":[]}"""), schema))
        assertFalse(JsonText.satisfies(JSONObject("""{"translation":"a"}"""), schema))
        assertFalse(JsonText.satisfies(JSONObject("""{"translation":null,"expressions":[]}"""), schema))
    }

    @Test fun satisfiesRequiresArrayType() {
        assertFalse(JsonText.satisfies(JSONObject("""{"translation":"a","expressions":"none"}"""), schema))
    }
}
