package com.englus.app.feed

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP with manual redirect following.
 *
 * HttpURLConnection follows redirects on its own but silently refuses to switch
 * protocols (http -> https). Podcast enclosures routinely chain through tracking
 * hosts and do exactly that — the NPR feed's URLs pass through two of them — and
 * the failure looks like an empty download rather than an error.
 */
object Http {

    class HttpException(
        val code: Int,
        val detail: String,
        /** From a Retry-After header, when the server sent one. */
        val retryAfterMs: Long? = null,
    ) : Exception(describe(code, detail)) {

        /** Worth trying again without the user changing anything. */
        val transient: Boolean get() = code in RETRYABLE
    }

    private const val MAX_REDIRECTS = 8
    private const val UA = "englus/0.1 (personal study app)"

    /**
     * Statuses that mean "not now" rather than "not like that".
     *
     * 503 is the one that prompted this: the server itself replies "spikes in
     * demand are usually temporary, please try again later", which is a retry
     * instruction being handed to a person instead of being carried out.
     */
    private val RETRYABLE = setOf(429, 500, 502, 503, 504)

    private const val MAX_ATTEMPTS = 3
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 4_000L

    /**
     * The error a person can act on.
     *
     * The server's own text is written for whoever is integrating the API, in
     * English, and for the common failures it says nothing the status code did
     * not. Where it does carry something specific - a rejected parameter, an
     * unknown model - it is kept, because throwing that away would leave the
     * user with a polite sentence and no way forward.
     */
    internal fun describe(code: Int, detail: String): String = when {
        // Gemini reports a bad key as 400, not 401, so the status alone routes
        // the most common failure of all to the least helpful branch.
        detail.contains("API key", ignoreCase = true) ->
            "API 키가 거부됐습니다. 설정에서 키를 확인하세요."
        else -> byStatus(code, detail)
    }

    private fun byStatus(code: Int, detail: String): String = when (code) {
        401, 403 -> "API 키가 거부됐습니다. 설정에서 키를 확인하세요."
        404 -> "모델을 찾을 수 없습니다. 설정에서 모델 이름을 확인하세요."
        429 -> "요청 한도를 넘었습니다. 잠시 후 다시 시도하세요."
        503 -> "Gemini 서버가 지금 붐빕니다. 잠시 후 다시 시도하거나, " +
            "설정에서 다른 모델로 바꿔보세요."
        500, 502, 504 -> "Gemini 서버 오류입니다 ($code). 잠시 후 다시 시도하세요."
        // 400 is where a bad model id or a malformed request lands, and there the
        // server's sentence is the only thing that identifies which.
        400 -> if (detail.isBlank()) "요청이 거부됐습니다 (400)." else "요청이 거부됐습니다 · $detail"
        else -> if (code > 0) "HTTP $code · $detail" else detail
    }

    /**
     * Strips `key=...` before anything reaches a message, a log or the screen.
     *
     * The Gemini API takes the key as a query parameter, so the failing URL
     * contains the user's credential verbatim — and error text is exactly what
     * ends up in a screenshot sent to someone for help.
     */
    internal fun redact(text: String): String =
        text.replace(Regex("([?&]key=)[^&\\s\"]+"), "$1***")

    private fun errorDetail(conn: HttpURLConnection, url: String): String {
        val body = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        // Prefer the server's own explanation: Gemini says "API key not valid"
        // where the URL alone says nothing actionable.
        if (body.isNullOrBlank()) return redact(url)
        return redact(extractMessage(body) ?: body.take(500))
    }

    /**
     * Pulls `error.message` out of the Google API error envelope.
     *
     * Without this the screen shows 40 lines of JSON around one useful sentence,
     * which reads as a crash rather than as "your key is wrong".
     */
    internal fun extractMessage(body: String): String? = runCatching {
        org.json.JSONObject(body)
            .optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Applied to the entry URL only, never to redirect targets.
     *
     * Upgrading a redirect target is not safe: BBC's CDN answers an https request
     * for a cleartext target with an empty 302, so forcing the scheme there swaps
     * a clear error for a silent dead end. The entry URL is different — cleartext
     * cannot work at all on Android, so upgrading it only ever helps.
     */
    internal fun normalize(url: String): String {
        var u = url
        // BBC's mediaselector takes the CDN protocol as a PATH segment. Leaving it
        // at /proto/http/ makes BBC redirect to a cleartext CDN host that Android
        // then refuses, no matter what scheme the request itself used.
        if (u.contains("/mediaselector/") && u.contains("/proto/http/")) {
            u = u.replace("/proto/http/", "/proto/https/")
        }
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u
    }

    fun open(url: String, timeoutMs: Int = 30_000): HttpURLConnection {
        var current = normalize(url)
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            when (val code = conn.responseCode) {
                in 200..299 -> return conn
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        val detail = errorDetail(conn, current)
                        conn.disconnect()
                        throw HttpException(code, detail)
                    }
                    conn.disconnect()
                    // Relative Location headers are legal; resolve against the current URL.
                    current = URL(URL(current), location).toString()
                }
                else -> {
                    val detail = errorDetail(conn, current)
                    val retryAfter = retryAfterMs(conn)
                    conn.disconnect()
                    throw HttpException(code, detail, retryAfter)
                }
            }
        }
        throw HttpException(-1, redact("too many redirects from $url"))
    }

    /**
     * POST a JSON body and return the response text.
     *
     * On a non-2xx the error stream is read and included: Gemini reports a wrong
     * model id or a rejected key as a JSON body, and swallowing it would leave
     * the user with "실패" and nothing to act on.
     */
    fun postJson(url: String, body: String, timeoutMs: Int = 60_000): String =
        withRetry { postOnce(url, body, timeoutMs) }

    /**
     * Retries the failures that are about timing rather than about the request.
     *
     * Generating content has no side effect, so repeating it is safe. Waiting is
     * the whole remedy for a 503, and making the user perform the wait - reading
     * an English apology, then pressing the button again - is handing them a job
     * the app can do in three seconds.
     *
     * Bounded deliberately low. A model that is busy for minutes will still be
     * busy after a minute of retries, and by then the learner deserves to be
     * told rather than watched over a spinner.
     */
    internal fun <T> withRetry(sleep: (Long) -> Unit = ::sleepQuietly, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                attempt++
                if (!e.transient || attempt >= MAX_ATTEMPTS) throw e
                // Honour Retry-After when the server names a delay, but never let
                // it park the app: a header asking for two minutes is not
                // something to silently obey behind a spinner.
                sleep(
                    (e.retryAfterMs ?: (BASE_BACKOFF_MS shl (attempt - 1)))
                        .coerceAtMost(MAX_BACKOFF_MS)
                )
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private fun postOnce(url: String, body: String, timeoutMs: Int): String {
        val conn = (URL(normalize(url)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", UA)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw HttpException(code, errorDetail(conn, url), retryAfterMs(conn))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Retry-After, in seconds or as an HTTP date. Null when absent or unusable. */
    private fun retryAfterMs(conn: HttpURLConnection): Long? {
        val header = conn.getHeaderField("Retry-After")?.trim().orEmpty()
        if (header.isEmpty()) return null
        header.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        val date = runCatching { conn.getHeaderFieldDate("Retry-After", 0L) }.getOrDefault(0L)
        if (date <= 0L) return null
        return (date - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun getText(url: String, timeoutMs: Int = 30_000): String = withRetry {
        open(url, timeoutMs).let { conn ->
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    fun <T> get(url: String, block: (InputStream, Long) -> T): T {
        val conn = open(url)
        return try {
            val length = conn.contentLengthLong
            conn.inputStream.use { block(it, length) }
        } finally {
            conn.disconnect()
        }
    }
}
