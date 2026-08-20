package com.englus.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.asr.ModelStore
import com.englus.app.asr.TranscriptParser
import com.englus.asr.AudioDecoder
import com.englus.asr.WhisperNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whisper output -> the rows the player reads.
 *
 * The part worth testing is token -> word merging. Whisper emits sub-word tokens
 * (" Plan", "et", " Money"); if they are not glued back together the karaoke
 * highlight lands on "et" and sentence-level seeking drifts. Nothing about the
 * UI would make that failure obvious.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptPipelineTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asset(name: String): Uri {
        val out = File(context.cacheDir, name)
        if (!out.exists() || out.length() == 0L) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(out)
    }

    @Test
    fun mergesTokensIntoWholeWordsWithUsableTimings() {
        val model = ModelStore(context).preferred()
        assumeTrue("no model on device — push one into files/models", model != null)

        val pcm = AudioDecoder.decode(context, asset("jfk.wav"))
        val ptr = WhisperNative.initContext(
            model!!.absolutePath, false, WhisperNative.Aheads.forModelFile(model.name)
        )
        assertTrue("model failed to load", ptr != 0L)

        val json = try {
            WhisperNative.transcribe(
                ptr = ptr,
                audio = pcm.samples,
                nSamples = pcm.sampleCount,
                nThreads = 4,
                language = "en",
                tokenTimestamps = true,
                useDtw = false,
                maxLen = 0,
                splitOnWord = true,
            )
        } finally {
            WhisperNative.freeContext(ptr)
        }

        val sentences = TranscriptParser.parse(episodeId = 1L, json = json)
        assertTrue("no sentences produced", sentences.isNotEmpty())

        val text = sentences.joinToString(" ") { it.text }.lowercase()
        assertTrue("unexpected transcript: $text", text.contains("ask not what your country"))

        // position must be dense and ordered — the player seeks and steps by it
        sentences.forEachIndexed { i, s -> assertEquals("position gap at $i", i, s.position) }

        val words = sentences.flatMap { it.words }
        assertTrue("expected words, got ${words.size}", words.size >= 15)

        // No leading/trailing whitespace, and no empty fragments: both are signs
        // the token merge leaked sub-word pieces through.
        words.forEach { w ->
            assertEquals("word not trimmed: '${w.text}'", w.text.trim(), w.text)
            assertTrue("empty word", w.text.isNotEmpty())
            assertTrue("word ${w.text}: t1 < t0", w.endMs >= w.startMs)
        }

        // The real check: whole words, not fragments. JFK's line has these, and a
        // broken merge yields "ask", "ing" style splinters instead.
        val joined = words.joinToString(" ") { it.text }.lowercase()
        listOf("country", "ask", "you").forEach { expected ->
            assertTrue("'$expected' missing from merged words: $joined", joined.contains(expected))
        }

        // Words must advance through the sentence, or the highlight jumps around.
        sentences.filter { it.words.size > 2 }.forEach { s ->
            var prev = -1L
            s.words.forEach { w ->
                assertTrue("words out of order in '${s.text}'", w.startMs >= prev)
                prev = w.startMs
            }
        }
    }
}
