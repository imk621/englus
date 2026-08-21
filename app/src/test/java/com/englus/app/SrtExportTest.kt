package com.englus.app

import com.englus.app.data.Sentence
import com.englus.app.data.Word
import com.englus.app.ui.quality.buildWordSrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SRT is the only way to check word timings against the audio, so a
 * malformed one would leave that unverifiable. SRT is strict: index, timestamp
 * line with a comma before milliseconds, text, blank line.
 */
class SrtExportTest {

    private fun sentence(vararg words: Word) =
        Sentence(id = 1, episodeId = 1, position = 0, text = "x", startMs = 0, endMs = 0, words = words.toList())

    @Test
    fun formatsTimestampsWithCommaMilliseconds() {
        val srt = buildWordSrt(listOf(sentence(Word("hello", 3_661_500, 3_662_000))))
        assertTrue("bad timestamp: $srt", srt.contains("01:01:01,500 --> 01:01:02,000"))
    }

    @Test
    fun numbersCuesSequentiallyAcrossSentences() {
        val srt = buildWordSrt(
            listOf(
                sentence(Word("a", 0, 300), Word("b", 300, 600)),
                sentence(Word("c", 600, 900)),
            )
        )
        val indices = srt.lines().filter { it.toIntOrNull() != null }.map { it.toInt() }
        assertEquals(listOf(1, 2, 3), indices)
    }

    @Test
    fun givesShortWordsAReadableFloorDuration() {
        // A 40 ms cue flashes past unreadably; the floor is what makes the check possible.
        val srt = buildWordSrt(listOf(sentence(Word("of", 1_000, 1_040))))
        assertTrue("floor not applied: $srt", srt.contains("00:00:01,000 --> 00:00:01,250"))
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals("", buildWordSrt(emptyList()))
        assertEquals("", buildWordSrt(listOf(sentence())))
    }
}
