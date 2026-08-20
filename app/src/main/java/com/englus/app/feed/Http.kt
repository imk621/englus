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

    class HttpException(val code: Int, detail: String) :
        Exception(if (code > 0) "HTTP $code · $detail" else detail)

    private const val MAX_REDIRECTS = 8
    private const val UA = "englus/0.1 (personal study app)"

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
                    conn.disconnect()
                    throw HttpException(code, detail)
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
    fun postJson(url: String, body: String, timeoutMs: Int = 60_000): String {
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
                throw HttpException(code, errorDetail(conn, url))
            }
        } finally {
            conn.disconnect()
        }
    }

    fun getText(url: String, timeoutMs: Int = 30_000): String =
        open(url, timeoutMs).let { conn ->
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
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
