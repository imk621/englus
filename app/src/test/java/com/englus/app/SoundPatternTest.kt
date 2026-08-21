package com.englus.app

import com.englus.app.sound.SoundPattern
import com.englus.app.sound.SoundPatternDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector decides which drill a learner's failed sentences land in. Getting
 * it wrong is not fatal — a sentence in the wrong bucket is still a sentence
 * worth re-listening to — but a pattern that never fires is a drill that never
 * exists.
 */
class SoundPatternTest {

    private fun patterns(s: String) = SoundPatternDetector.patterns(s)

    @Test
    fun findsContractions() {
        assertTrue(SoundPattern.CONTRACTION in patterns("I don't know what you're saying."))
        assertTrue(SoundPattern.CONTRACTION in patterns("We've been here before."))
        assertFalse(SoundPattern.CONTRACTION in patterns("I do not know."))
    }

    @Test
    fun findsColloquialReductions() {
        assertTrue(SoundPattern.GONNA in patterns("I'm going to tell you something."))
        assertTrue(SoundPattern.GONNA in patterns("There was a lot of noise."))
        assertTrue(SoundPattern.GONNA in patterns("You gotta be kidding."))
    }

    @Test
    fun findsWeakFunctionWords() {
        assertTrue(SoundPattern.WEAK_FORM in patterns("ask not what your country can do for you"))
        // A sentence of content words only has nothing to reduce.
        assertFalse(SoundPattern.WEAK_FORM in patterns("Dogs bark loudly."))
    }

    @Test
    fun findsLinkingAcrossWords() {
        assertTrue(SoundPattern.LINKING in patterns("Pick it up over there."))
        assertTrue(SoundPattern.LINKING in patterns("What an idea."))
    }

    @Test
    fun findsFlapT() {
        assertTrue(SoundPattern.FLAP_T in patterns("The water is better today."))
        assertTrue(SoundPattern.FLAP_T in patterns("I did not get it."))
    }

    @Test
    fun findsHDropContexts() {
        assertTrue(SoundPattern.H_DROP in patterns("Tell him the truth."))
        assertTrue(SoundPattern.H_DROP in patterns("I gave her the book."))
    }

    @Test
    fun reportsTheActualSpansSoTheDrillCanShowThem() {
        val hits = SoundPatternDetector.detect("I don't want to get it wrong.")
        val contraction = hits.first { it.pattern == SoundPattern.CONTRACTION }
        assertTrue("span not captured: ${contraction.spans}", contraction.spans.any { it.contains("don't") })

        val gonna = hits.first { it.pattern == SoundPattern.GONNA }
        assertTrue("span not captured: ${gonna.spans}", gonna.spans.any { it.contains("want to") })
    }

    @Test
    fun spansAreCappedSoTheUiStaysReadable() {
        val many = "to of for and or as at than that from can was were are am is"
        val hits = SoundPatternDetector.detect(many)
        hits.forEach { assertTrue("too many spans: ${it.spans}", it.spans.size <= 4) }
    }

    @Test
    fun emptyAndPunctuationOnlyInputAreSafe() {
        assertTrue(SoundPatternDetector.detect("").isEmpty())
        assertTrue(SoundPatternDetector.detect("... --- ").isEmpty())
    }

    @Test
    fun aRealMissedSentenceLandsInSeveralDrills() {
        // The JFK line the app has been tested against all along.
        val p = patterns("And so my fellow Americans ask not what your country can do for you")
        assertTrue("weak forms missed", SoundPattern.WEAK_FORM in p)
        assertTrue("linking missed", SoundPattern.LINKING in p)
    }
}
