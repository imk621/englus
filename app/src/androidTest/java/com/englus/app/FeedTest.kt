package com.englus.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.data.PRESET_FEEDS
import com.englus.app.feed.Http
import com.englus.app.feed.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedTest {

    private fun asset(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name)

    @Test
    fun parsesFeedVariantsAndSkipsNonAudioEnclosures() {
        val feed = asset("sample-feed.xml").use { RssParser.parse(it) }

        assertEquals("Sample Learning Podcast", feed.title)
        assertEquals("https://example.com/channel.png", feed.imageUrl)

        // The image-only item must not become an episode; feeds routinely enclose
        // cover art and transcripts alongside audio.
        assertEquals(3, feed.items.size)

        assertEquals("guid-001", feed.items[0].guid)
        assertEquals(2408, feed.items[0].durationSec)
        assertEquals(38_529_655L, feed.items[0].sizeBytes)
        assertTrue("pubDate not parsed", feed.items[0].publishedAt > 0)

        assertEquals(6 * 60 + 13, feed.items[1].durationSec)
        assertEquals(1 * 3600 + 2 * 60 + 3, feed.items[2].durationSec)

        // No <guid>: the audio URL has to stand in, or every refresh would insert
        // the same episode again.
        assertEquals("https://example.com/ep3.mp3", feed.items[2].guid)
    }

    @Test
    fun durationParsingHandlesTheFormatsFeedsActuallyUse() {
        assertEquals(0, RssParser.parseDuration(null))
        assertEquals(0, RssParser.parseDuration(""))
        assertEquals(90, RssParser.parseDuration("90"))
        assertEquals(90, RssParser.parseDuration("1:30"))
        assertEquals(3661, RssParser.parseDuration("1:01:01"))
        assertEquals(0, RssParser.parseDuration("nonsense"))
    }

    /**
     * Real network. Podcast enclosures are not served from the podcast's own
     * host — they chain through tracking redirectors (the NPR feed passes through
     * three) — and this client follows redirects by hand. Getting that wrong
     * yields an empty file rather than an error, so it is checked against a real
     * chain rather than a stub.
     */
    @Test
    fun followsRedirectChainToTheRealAudioHost() {
        val feed = Http.get("https://feeds.npr.org/510289/podcast.xml") { s, _ -> RssParser.parse(s) }
        val enclosure = feed.items.first().audioUrl
        val originalHost = java.net.URL(enclosure).host

        val conn = Http.open(enclosure)
        try {
            assertTrue("expected 2xx, got ${conn.responseCode}", conn.responseCode in 200..299)
            assertTrue(
                "expected a redirect away from $originalHost, ended at ${conn.url.host}",
                conn.url.host != originalHost,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** http:// is dead on Android; the client must upgrade rather than fail obscurely. */
    @Test
    fun upgradesCleartextUrlsToHttps() {
        val conn = Http.open("http://feeds.npr.org/510289/podcast.xml")
        try {
            assertEquals("https", conn.url.protocol)
            assertTrue(conn.responseCode in 200..299)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun rewritesBbcMediaselectorProtocolSegment() {
        val bbc = "http://open.live.bbc.co.uk/mediaselector/6/redir/version/2.0/" +
            "mediaset/audio-nondrm-download-rss-low/proto/http/vpid/p0p4779b.mp3"
        val normalized = Http.normalize(bbc)
        assertTrue("scheme not upgraded: $normalized", normalized.startsWith("https://"))
        assertTrue("proto segment not rewritten: $normalized", normalized.contains("/proto/https/"))

        // Unrelated URLs must not be touched beyond the scheme.
        assertEquals(
            "https://example.com/a/proto/http/b.mp3",
            Http.normalize("https://example.com/a/proto/http/b.mp3"),
        )
    }

    /**
     * The download path, checked without downloading: an enclosure that only
     * resolves over cleartext is dead on Android, and the failure appears at the
     * end of a redirect chain rather than at the URL in the feed. Headers only —
     * the point is where the chain lands, not the audio.
     */
    @Test
    fun presetFeedEnclosuresResolveOverHttps() {
        PRESET_FEEDS.forEach { preset ->
            val feed = Http.get(preset.feedUrl) { s, _ -> RssParser.parse(s) }
            val conn = Http.open(feed.items.first().audioUrl)
            try {
                assertTrue(
                    "${preset.title}: enclosure gave ${conn.responseCode}",
                    conn.responseCode in 200..299,
                )
                assertEquals("${preset.title}: ended on cleartext", "https", conn.url.protocol)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** The starter ladder is only useful if the feeds are alive and parseable. */
    @Test
    fun presetFeedsResolveAndParse() {
        PRESET_FEEDS.forEach { preset ->
            val feed = Http.get(preset.feedUrl) { stream, _ -> RssParser.parse(stream) }
            assertTrue("${preset.title}: no title", feed.title.isNotBlank())
            assertTrue("${preset.title}: no episodes", feed.items.isNotEmpty())
            assertTrue(
                "${preset.title}: item without audio url",
                feed.items.all { it.audioUrl.startsWith("http") },
            )
        }
    }
}
