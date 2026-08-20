package com.englus.app

import com.englus.app.dictation.DiffStatus
import com.englus.app.dictation.WordDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scoring engine decides which of the three diagnoses a learner gets, so its
 * failure modes are not cosmetic: a dropped article that cascades into "every
 * later word wrong" would turn a near-perfect answer into a VOCAB verdict.
 */
class WordDiffTest {

    @Test
    fun exactMatchIsPerfect() {
        val d = WordDiff.compare("Ask not what your country can do for you", "ask not what your country can do for you")
        assertTrue(d.isPerfect)
        assertEquals(1f, d.accuracy, 0.001f)
        assertTrue(d.reference.all { it.status == DiffStatus.MATCH })
    }

    @Test
    fun punctuationAndCaseAreNotScored() {
        // You cannot hear a comma. Scoring it would measure typing, not listening.
        val d = WordDiff.compare("In fact, no one gets to live an easy life.", "in fact no one gets to live an easy life")
        assertTrue("punctuation counted as error", d.isPerfect)
    }

    @Test
    fun apostrophesAreKept() {
        // "dont" is a real listening/spelling miss, not a punctuation artifact.
        val d = WordDiff.compare("I don't know", "I dont know")
        assertFalse(d.isPerfect)
        assertEquals(2, d.correctWords)
    }

    @Test
    fun aMissedWordCostsOneWordNotTheRest() {
        // The whole reason for alignment instead of position-by-position scoring.
        val d = WordDiff.compare("the cat sat on the mat", "cat sat on the mat")
        assertEquals(5, d.correctWords)
        assertEquals(6, d.totalWords)
        assertEquals(DiffStatus.MISSING, d.reference.first().status)
        assertTrue(d.reference.drop(1).all { it.status == DiffStatus.MATCH })
    }

    @Test
    fun anExtraWordIsMarkedOnTheAnswerOnly() {
        val d = WordDiff.compare("cat sat on mat", "cat sat down on mat")
        assertEquals(4, d.correctWords)
        assertEquals(4, d.totalWords)
        assertFalse("extra words must not count as perfect", d.isPerfect)
        assertEquals(1, d.hypothesis.count { it.status == DiffStatus.EXTRA })
        assertTrue(d.reference.all { it.status == DiffStatus.MATCH })
    }

    @Test
    fun substitutionMarksBothSides() {
        val d = WordDiff.compare("a survivor and a hero", "a survivor and a zero")
        assertEquals(4, d.correctWords)
        assertEquals(DiffStatus.WRONG, d.reference.last().status)
        assertEquals(DiffStatus.WRONG, d.hypothesis.last().status)
    }

    @Test
    fun emptyAnswerLosesEverythingWithoutCrashing() {
        val d = WordDiff.compare("you have been a victim", "")
        assertEquals(0, d.correctWords)
        assertEquals(5, d.totalWords)
        assertEquals(0f, d.accuracy, 0.001f)
        assertTrue(d.reference.all { it.status == DiffStatus.MISSING })
        assertTrue(d.hypothesis.isEmpty())
    }

    @Test
    fun emptyReferenceDoesNotDivideByZero() {
        val d = WordDiff.compare("", "something")
        assertEquals(0f, d.accuracy, 0.001f)
        assertEquals(1, d.hypothesis.count { it.status == DiffStatus.EXTRA })
    }

    @Test
    fun curlyApostrophesFromTranscriptsMatchTypedOnes() {
        // Whisper emits U+2019; keyboards produce U+0027.
        assertEquals(WordDiff.normalize("don’t"), WordDiff.normalize("don't"))
        assertTrue(WordDiff.compare("don’t stop", "don't stop").isPerfect)
    }

    @Test
    fun realisticNearMissScoresHigh() {
        val d = WordDiff.compare(
            "And so far, including this episode, we have circled the globe",
            "and so far including this episode we have circle the globe",
        )
        // One real error ("circle" for "circled") out of eleven words.
        assertEquals(10, d.correctWords)
        assertEquals(11, d.totalWords)
        assertTrue(d.accuracy > 0.9f)
    }
}
