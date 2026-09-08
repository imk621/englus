package com.englus.app

import com.englus.app.level.LevelItem
import com.englus.app.level.LevelTest
import com.englus.app.vocab.Cefr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The control items are the entire reason to run a test rather than simply ask,
 * so the correction they drive is what has to be right. Everything else in here
 * is arithmetic; this is the measurement.
 */
class LevelTestTest {

    private fun items(): List<LevelItem> =
        LevelTest.build(RealRanks, (1..24).map { "nonword$it" }, Random(7))

    @Test
    fun buildsEveryBandPlusControls() {
        val built = items()
        (0..4).forEach { band ->
            assertEquals("band $band", LevelTest.PER_BAND, built.count { it.band == band })
        }
        assertEquals(LevelTest.CONTROLS, built.count { it.isControl })
        // Sampled from the real list, so every real item must be a real word.
        built.filter { !it.isControl }.forEach {
            assertTrue("${it.word} is not in the frequency list", RealRanks.contains(it.word))
        }
    }

    @Test
    fun sampledWordsComeFromTheRightBand() {
        val built = items()
        val bounds = listOf(1..1000, 1001..2000, 2001..3000, 3001..5000, 5001..10000)
        built.filter { !it.isControl }.forEach { item ->
            val rank = RealRanks.rank(item.word)!!
            assertTrue(
                "${item.word} (rank $rank) is not in band ${item.band}",
                rank in bounds[item.band!!],
            )
        }
    }

    /** Honest answers: claiming nothing that does not exist leaves the score alone. */
    @Test
    fun noOverclaimingMeansNoCorrection() {
        val built = items()
        val claimed = built.filter { it.band == 0 || it.band == 1 }.map { it.word }.toSet()

        val result = LevelTest.score(built, claimed, now = 1L)
        assertEquals(0f, result.falseAlarmRate, 1e-6f)
        assertEquals(1f, result.bands[0].corrected, 1e-6f)
        assertEquals(1f, result.bands[1].corrected, 1e-6f)
        assertEquals(0f, result.bands[4].corrected, 1e-6f)
        assertEquals("1000 + 1000", 2000, result.vocabularySize)
    }

    /**
     * The case the whole design exists for. Someone who says yes to everything
     * must not come out as C2; the control items give that away and the score
     * has to collapse.
     */
    @Test
    fun claimingEverythingScoresNothing() {
        val built = items()
        val result = LevelTest.score(built, built.map { it.word }.toSet(), now = 1L)

        assertEquals(1f, result.falseAlarmRate, 1e-6f)
        assertEquals("saying yes to everything bought vocabulary", 0, result.vocabularySize)
        assertTrue("a meaningless result was not flagged", result.unreliable)
    }

    @Test
    fun partialOverclaimingDiscountsEveryBand() {
        val built = items()
        // Knows all of K1, plus half the non-words: the inflation is visible.
        val claimed = (
            built.filter { it.band == 0 }.map { it.word } +
                built.filter { it.isControl }.take(5).map { it.word }
            ).toSet()

        val result = LevelTest.score(built, claimed, now = 1L)
        assertEquals(0.5f, result.falseAlarmRate, 1e-6f)
        // A perfect band stays perfect - (1 - .5) / (1 - .5) = 1.
        assertEquals(1f, result.bands[0].corrected, 1e-6f)
        assertTrue("still usable at half", !result.unreliable)
    }

    @Test
    fun correctionFormulaBehavesAtTheEdges() {
        assertEquals(1f, LevelTest.correct(1f, 0f), 1e-6f)
        assertEquals(0f, LevelTest.correct(0f, 0f), 1e-6f)
        // Claiming fewer real words than non-words cannot go negative.
        assertEquals(0f, LevelTest.correct(0.2f, 0.6f), 1e-6f)
        assertEquals(0f, LevelTest.correct(0.9f, 1f), 1e-6f)
        // Half the real words against a fifth of the non-words.
        assertEquals(0.375f, LevelTest.correct(0.5f, 0.2f), 1e-4f)
    }

    @Test
    fun vocabularySizeMapsToABand() {
        assertEquals(Cefr.A2, LevelTest.cefrForSize(900))
        assertEquals(Cefr.B1, LevelTest.cefrForSize(2000))
        assertEquals(Cefr.B2, LevelTest.cefrForSize(3200))
        assertEquals(Cefr.C1, LevelTest.cefrForSize(6000))
        assertEquals(Cefr.C2, LevelTest.cefrForSize(9000))
    }

    /** A retake must not be the same twenty words the learner just memorised. */
    @Test
    fun retakingDrawsDifferentWords() {
        val first = LevelTest.build(RealRanks, listOf("nonword1"), Random(1)).map { it.word }.toSet()
        val second = LevelTest.build(RealRanks, listOf("nonword1"), Random(2)).map { it.word }.toSet()
        val shared = first.intersect(second).size
        assertTrue("the two tests were nearly identical ($shared shared)", shared < first.size / 2)
    }
}
