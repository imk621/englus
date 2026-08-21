package com.englus.app

import com.englus.app.data.VocabBandStat
import com.englus.app.vocab.Band
import com.englus.app.vocab.Cefr
import com.englus.app.vocab.DifficultyEstimator
import com.englus.app.vocab.Lemmatizer
import com.englus.app.vocab.VocabRepository
import com.englus.app.data.VocabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {

    private val lem = Lemmatizer(RealRanks)

    /**
     * The measured calibration set, kept as a test.
     *
     * These six numbers came from profiling real texts of known level, including
     * the same Poe story in a simplified and an original edition. They are the
     * only evidence the thresholds rest on, so they belong in the build: anyone
     * who moves a threshold has to move it past this.
     */
    @Test
    fun thresholdsReproduceTheCalibrationSet() {
        data class Sample(val name: String, val k2: Float, val avgLen: Float, val expect: Cefr)
        listOf(
            Sample("VOA Learning English, simplified Usher", 0.896f, 18.5f, Cefr.A2),
            Sample("Alice in Wonderland", 0.873f, 18.8f, Cefr.B1),
            Sample("BBC 6 Minute English transcript", 0.810f, 13.4f, Cefr.B2),
            Sample("Frankenstein", 0.770f, 24.0f, Cefr.C1),
            Sample("Poe, original Usher", 0.766f, 20.2f, Cefr.C1),
            Sample("Darwin, Origin of Species", 0.684f, 21.1f, Cefr.C2),
        ).forEach { s ->
            assertEquals(s.name, s.expect, DifficultyEstimator.cefr(s.k2, s.avgLen))
        }
    }

    /** The pair that would expose a metric measuring style instead of difficulty. */
    @Test
    fun separatesTheSameStorySimplifiedFromOriginal() {
        val simplified = DifficultyEstimator.cefr(0.896f, 18.5f)
        val original = DifficultyEstimator.cefr(0.766f, 20.2f)
        assertTrue(
            "simplified ($simplified) should rate easier than original ($original)",
            simplified.ordinal < original.ordinal,
        )
    }

    /** Sentence length may nudge a borderline text; it must never overturn one. */
    @Test
    fun sentenceLengthCannotOverturnVocabulary() {
        listOf(1f, 8f, 16f, 30f, 80f).forEach { len ->
            assertEquals(
                "avgLen $len moved a clearly hard text",
                Cefr.C2,
                DifficultyEstimator.cefr(0.60f, len),
            )
            assertEquals(
                "avgLen $len moved a clearly easy text",
                Cefr.A2,
                DifficultyEstimator.cefr(0.97f, len),
            )
        }
    }

    @Test
    fun profilesRealSentences() {
        val simple = listOf(
            "The man walked to the shop and bought some bread.",
            "It was a good day and the sun was very warm.",
            "She said she would come back later to see him.",
        )
        val p = DifficultyEstimator.profile(simple, lem, RealRanks)

        assertEquals(simple.size, 3)
        assertTrue("k1 coverage too low for basic English: ${p.k1Coverage}", p.k1Coverage > 0.85f)
        assertEquals(0f, p.offListRate, 1e-6f)
        assertTrue("no tokens counted", p.tokenCount > 20)
        assertEquals(p.tokenCount, p.bandCounts.values.sum())
    }

    @Test
    fun countsOffListWordsAsOffList() {
        val p = DifficultyEstimator.profile(
            listOf("The zooplankton and the phytoplankton drifted past the boat."),
            lem, RealRanks,
        )
        assertTrue("rare words not detected", (p.bandCounts[Band.OFF] ?: 0) >= 2)
    }

    @Test
    fun emptyTextDoesNotDivideByZero() {
        val p = DifficultyEstimator.profile(listOf("", "   ", "123 456"), lem, RealRanks)
        assertEquals(0, p.tokenCount)
        assertEquals(0f, p.k2Coverage, 1e-6f)
    }

    @Test
    fun bandsCoverEveryRankWithoutOverlap() {
        assertEquals(Band.K1, Band.of(1))
        assertEquals(Band.K1, Band.of(1000))
        assertEquals(Band.K2, Band.of(1001))
        assertEquals(Band.K2, Band.of(2000))
        assertEquals(Band.K3, Band.of(2001))
        assertEquals(Band.K5, Band.of(5000))
        assertEquals(Band.K10, Band.of(10000))
        assertEquals(Band.OFF, Band.of(null))
    }

    @Test
    fun vocabularySizeIgnoresBandsWithTooLittleEvidence() {
        val thin = listOf(VocabBandStat(band = 0, known = 5, unknown = 0, met = 5))
        assertNull("estimated from 5 words", VocabRepository.estimateSize(thin))

        val enough = listOf(
            VocabBandStat(band = 0, known = 40, unknown = 0, met = 40),  // all of K1
            VocabBandStat(band = 1, known = 10, unknown = 10, met = 20), // half of K2
            VocabBandStat(band = 5, known = 30, unknown = 0, met = 30),  // off-list: unbounded
        )
        // 1000 + 500; the off-list band contributes nothing because it has no size.
        assertEquals(1500, VocabRepository.estimateSize(enough))
    }

    @Test
    fun evidenceRulesNeedTwoCleanHitsAndRespectAHandJudgement() {
        assertEquals(VocabState.UNKNOWN, VocabRepository.stateFor(correct = 0, missed = 1, manual = 0))
        assertEquals(VocabState.LEARNING, VocabRepository.stateFor(correct = 1, missed = 0, manual = 0))
        assertEquals(VocabState.KNOWN, VocabRepository.stateFor(correct = 2, missed = 0, manual = 0))
        assertEquals(VocabState.LEARNING, VocabRepository.stateFor(correct = 2, missed = 2, manual = 0))
        // A hand judgement overrides evidence in both directions.
        assertEquals(VocabState.KNOWN, VocabRepository.stateFor(correct = 0, missed = 9, manual = 1))
        assertEquals(VocabState.UNKNOWN, VocabRepository.stateFor(correct = 9, missed = 0, manual = 2))
    }
}
