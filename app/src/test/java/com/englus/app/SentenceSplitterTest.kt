package com.englus.app

import com.englus.app.ocr.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR text is the messiest input the app takes: hard line breaks from page
 * layout, hyphenated words split across lines, abbreviations that look like
 * sentence ends. Every one of these produces garbage sentences that are then
 * translated, reviewed and remembered wrong.
 */
class SentenceSplitterTest {

    @Test
    fun splitsPlainSentences() {
        val out = SentenceSplitter.split("This is one. This is two! Is this three?")
        assertEquals(listOf("This is one.", "This is two!", "Is this three?"), out)
    }

    @Test
    fun joinsLineBreaksFromPageLayout() {
        // The single most common OCR artefact: a sentence wrapped by the column.
        val out = SentenceSplitter.split("The quick brown fox\njumps over the lazy dog.")
        assertEquals(listOf("The quick brown fox jumps over the lazy dog."), out)
    }

    @Test
    fun rejoinsHyphenatedWordsAcrossLines() {
        val out = SentenceSplitter.split("This is an inter-\nnational agreement.")
        assertEquals(listOf("This is an international agreement."), out)
    }

    @Test
    fun doesNotSplitOnCommonAbbreviations() {
        val out = SentenceSplitter.split("Dr. Smith arrived. He was late.")
        assertEquals(listOf("Dr. Smith arrived.", "He was late."), out)
    }

    @Test
    fun doesNotSplitOnDottedAcronyms() {
        val out = SentenceSplitter.split("The U.S. economy grew. Everyone noticed.")
        assertEquals(2, out.size)
        assertTrue("acronym split: $out", out[0].contains("U.S. economy"))
    }

    @Test
    fun keepsClosingQuotesWithTheirSentence() {
        val out = SentenceSplitter.split("She said \"go now.\" Then she left.")
        assertEquals(2, out.size)
        assertTrue("quote orphaned: $out", out[0].endsWith("\""))
    }

    @Test
    fun handlesMultipleTerminators() {
        val out = SentenceSplitter.split("Really?! I had no idea.")
        assertEquals(listOf("Really?!", "I had no idea."), out)
    }

    @Test
    fun dropsNoiseWithoutLetters() {
        // Page numbers and rules that OCR happily reports as text.
        val out = SentenceSplitter.split("Real sentence here. --- ... ")
        assertEquals(listOf("Real sentence here."), out)
    }

    @Test
    fun breaksUpTextWithNoPunctuationAtAll() {
        val runOn = List(120) { "word" }.joinToString(" ")
        val out = SentenceSplitter.split(runOn)
        assertTrue("run-on not broken: ${out.size} chunk(s)", out.size > 1)
        assertTrue("chunk too long", out.all { it.length <= 300 })
    }

    @Test
    fun emptyAndWhitespaceInputYieldNothing() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split("   \n\n  ").isEmpty())
    }

    @Test
    fun normalizeCollapsesLayoutWhitespace() {
        assertEquals("a b c", SentenceSplitter.normalize("a   b\n  c"))
    }

    @Test
    fun realisticPageExtract() {
        val page = """
            In fact, no one gets to live an easy
            life. You've been a victim, a sur-
            vivor, and a hero. That is enough.
        """.trimIndent()
        val out = SentenceSplitter.split(page)
        assertEquals(3, out.size)
        assertTrue("hyphen not rejoined: ${out[1]}", out[1].contains("survivor"))
        assertTrue("wrap not joined: ${out[0]}", out[0].contains("easy life"))
    }
}
