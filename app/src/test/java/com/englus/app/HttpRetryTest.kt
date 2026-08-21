package com.englus.app

import com.englus.app.feed.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 503 from Gemini says "spikes in demand are usually temporary, please try
 * again later". That is a retry instruction, and it was being handed to the
 * person holding the phone instead of being carried out.
 */
class HttpRetryTest {

    private fun boom(code: Int, retryAfterMs: Long? = null): Nothing =
        throw Http.HttpException(code, "detail", retryAfterMs)

    @Test
    fun retriesUntilItSucceeds() {
        var calls = 0
        val waits = mutableListOf<Long>()

        val result = Http.withRetry(sleep = { waits += it }) {
            calls++
            if (calls < 3) boom(503)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, calls)
        // Backoff doubles rather than hammering a server that just said it is busy.
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun givesUpRatherThanSpinningForever() {
        var calls = 0
        val e = runCatching {
            Http.withRetry(sleep = {}) { calls++; boom(503) }
        }.exceptionOrNull()

        assertTrue(e is Http.HttpException)
        assertEquals("attempt count changed", 3, calls)
    }

    /**
     * A rejected key is not a timing problem. Retrying it wastes the learner's
     * time and, on a metered quota, their allowance.
     */
    @Test
    fun doesNotRetryWhatWillNeverSucceed() {
        listOf(400, 401, 403, 404).forEach { code ->
            var calls = 0
            runCatching { Http.withRetry(sleep = {}) { calls++; boom(code) } }
            assertEquals("retried a permanent failure ($code)", 1, calls)
        }
    }

    @Test
    fun honoursRetryAfterButWillNotParkTheApp() {
        val waits = mutableListOf<Long>()
        runCatching {
            Http.withRetry(sleep = { waits += it }) { boom(503, retryAfterMs = 120_000) }
        }
        assertTrue("nothing waited", waits.isNotEmpty())
        assertTrue("a two minute header was obeyed: $waits", waits.all { it <= 4_000L })
    }

    @Test
    fun transientClassificationMatchesTheStatuses() {
        listOf(429, 500, 502, 503, 504).forEach {
            assertTrue("$it should be retryable", Http.HttpException(it, "").transient)
        }
        listOf(400, 401, 403, 404, 418).forEach {
            assertFalse("$it should not be retryable", Http.HttpException(it, "").transient)
        }
    }

    /**
     * The server's English sentence is replaced where the status already says
     * everything, and kept where it is the only thing identifying the problem.
     */
    @Test
    fun explainsFailuresInTermsTheLearnerCanActOn() {
        val busy = Http.describe(503, "This model is currently experiencing high demand.")
        assertFalse("server English leaked through", busy.contains("high demand"))
        assertTrue("no way forward offered", busy.contains("모델"))

        assertTrue(Http.describe(403, "API key not valid").contains("키"))
        assertTrue(Http.describe(404, "").contains("모델"))

        // 400 carries the specific reason, so it must survive.
        val bad = Http.describe(400, "Unknown name \"responseSchema\"")
        assertTrue("specific reason discarded: $bad", bad.contains("responseSchema"))

        // Gemini answers a bad key with 400, so the status alone would file the
        // single most common failure under "요청이 거부됐습니다".
        val badKey = Http.describe(400, "API key not valid. Please pass a valid API key.")
        assertTrue("bad key not recognised: $badKey", badKey.contains("키"))
        assertFalse("English leaked through: $badKey", badKey.contains("API key not"))
    }
}
