package com.englus.app

import com.englus.app.feed.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The Gemini API takes the key as a query parameter, so a failing request puts
 * the user's credential inside the error text — which is exactly what gets
 * screenshotted and sent to someone for help.
 */
class HttpRedactTest {

    @Test
    fun stripsApiKeyFromUrls() {
        val redacted = Http.redact(
            "HTTP 400 for https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSySECRET123"
        )
        assertFalse("key leaked: $redacted", redacted.contains("AIzaSySECRET123"))
        assertEquals(
            "HTTP 400 for https://generativelanguage.googleapis.com/v1beta/models?key=***",
            redacted,
        )
    }

    @Test
    fun stripsKeyWhenItIsNotTheFirstParameter() {
        val redacted = Http.redact("https://host/v1beta/models/x:generateContent?alt=json&key=SECRET")
        assertFalse(redacted.contains("SECRET"))
        assertEquals("https://host/v1beta/models/x:generateContent?alt=json&key=***", redacted)
    }

    @Test
    fun stripsKeyInsideAJsonErrorBody() {
        val redacted = Http.redact("""{"error":{"message":"bad url ?key=SECRET here"}}""")
        assertFalse(redacted.contains("SECRET"))
    }

    @Test
    fun leavesUnrelatedTextAlone() {
        val text = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}"""
        assertEquals(text, Http.redact(text))
    }

    @Test
    fun doesNotEatSurroundingParameters() {
        // A greedy pattern would swallow everything after the key.
        val redacted = Http.redact("https://host/p?key=SECRET&next=keepme")
        assertEquals("https://host/p?key=***&next=keepme", redacted)
    }
}

class HttpErrorMessageTest {

    @Test
    fun extractsTheOneUsefulSentenceFromGoogleErrorEnvelope() {
        val body = """
            {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
             "status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
             "reason":"API_KEY_INVALID"}]}}
        """.trimIndent()
        assertEquals(
            "API key not valid. Please pass a valid API key.",
            Http.extractMessage(body),
        )
    }

    @Test
    fun returnsNullForBodiesThatAreNotThatShape() {
        assertEquals(null, Http.extractMessage("<html>502 Bad Gateway</html>"))
        assertEquals(null, Http.extractMessage("""{"ok":true}"""))
        assertEquals(null, Http.extractMessage(""))
    }
}
