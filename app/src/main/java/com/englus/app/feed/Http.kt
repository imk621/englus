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

    class HttpException(val code: Int, val url: String) :
        Exception("HTTP $code for $url")

    private const val MAX_REDIRECTS = 8
    private const val UA = "englus/0.1 (personal study app)"

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
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw HttpException(code, current)
                    // Relative Location headers are legal; resolve against the current URL.
                    current = URL(URL(current), location).toString()
                }
                else -> {
                    conn.disconnect()
                    throw HttpException(code, current)
                }
            }
        }
        throw HttpException(-1, "too many redirects from $url")
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
