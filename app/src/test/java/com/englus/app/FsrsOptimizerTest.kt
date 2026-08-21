package com.englus.app

import com.englus.app.srs.Fsrs
import com.englus.app.srs.FsrsOptimizer
import com.englus.app.srs.Rating
import com.englus.app.srs.ReviewSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A personalised scheduler that is wrong is worse than a generic one that is
 * right: it silently changes every future interval. So the fit must recover a
 * known answer from synthetic data, refuse to run on thin data, and never move
 * far from the prior on the strength of a handful of reviews.
 */
class FsrsOptimizerTest {

    /** Generates outcomes from a known true stability. */
    private fun synth(
        rating: Rating,
        trueStability: Double,
        n: Int,
        seed: Int = 7,
    ): List<ReviewSample> {
        val rng = Random(seed)
        return (0 until n).map {
            val days = 1 + rng.nextDouble() * trueStability * 3
            val r = FsrsOptimizer.retrievability(days, trueStability)
            ReviewSample(rating, days, rng.nextDouble() < r)
        }
    }

    @Test
    fun recoversAKnownStabilityFromSyntheticOutcomes() {
        val truth = 6.0
        val samples = Rating.entries.flatMap { synth(it, truth, 200, seed = it.value) }

        val result = FsrsOptimizer.optimize(samples)
        assertNotNull("optimizer refused usable data", result)
        result!!.initialStability.forEachIndexed { i, s ->
            assertTrue("w[$i] = $s did not recover $truth", kotlin.math.abs(s - truth) < truth * 0.5)
        }
    }

    @Test
    fun refusesThinData() {
        assertNull(FsrsOptimizer.optimize(synth(Rating.GOOD, 5.0, 10)))
        assertNull(FsrsOptimizer.optimize(emptyList()))
    }

    @Test
    fun refusesWhenNoSingleRatingHasEnoughSamples() {
        // 40 total but spread so thin that no rating clears the per-rating floor.
        val samples = Rating.entries.flatMap { synth(it, 5.0, 10, seed = it.value) }
        assertNull("fit ran on too few samples per rating", FsrsOptimizer.optimize(samples))
    }

    @Test
    fun reportsWhetherTheFitActuallyImproved() {
        val samples = Rating.entries.flatMap { synth(it, 6.0, 200, seed = it.value) }
        val result = FsrsOptimizer.optimize(samples)!!
        assertTrue(
            "loss did not improve: ${result.lossBefore} -> ${result.lossAfter}",
            result.improved,
        )
    }

    @Test
    fun doesNotRunAwayFromThePriorOnDegenerateData() {
        // Everything recalled, always: likelihood pushes stability to infinity.
        val samples = Rating.entries.flatMap { rating ->
            (0 until 50).map { ReviewSample(rating, 1.0, recalled = true) }
        }
        val result = FsrsOptimizer.optimize(samples)!!
        result.initialStability.forEachIndexed { i, s ->
            val prior = Fsrs.DEFAULT_WEIGHTS[i]
            assertTrue(
                "w[$i] escaped: $s vs prior $prior",
                s <= prior * 20 && s >= prior / 20,
            )
        }
    }

    @Test
    fun calibrationDetectsAnOverconfidentSchedule() {
        // Truth is half the assumed stability, so the learner forgets more than
        // the model predicts. Calibration must show actual below predicted.
        val assumed = Fsrs.DEFAULT_WEIGHTS[Rating.GOOD.value - 1]
        val samples = synth(Rating.GOOD, assumed / 2, 400)

        val buckets = FsrsOptimizer.calibration(samples)
        assertTrue("no buckets produced", buckets.isNotEmpty())

        val weighted = { f: (com.englus.app.srs.CalibrationBucket) -> Double ->
            buckets.sumOf { f(it) * it.count } / buckets.sumOf { it.count }
        }
        assertTrue(
            "overconfidence not detected: predicted ${weighted { it.predicted }} " +
                "vs actual ${weighted { it.actual }}",
            weighted { it.actual } < weighted { it.predicted },
        )
    }

    @Test
    fun calibrationIsWellFormed() {
        val samples = Rating.entries.flatMap { synth(it, 6.0, 100, seed = it.value) }
        FsrsOptimizer.calibration(samples).forEach { b ->
            assertTrue("predicted out of range", b.predicted in 0.0..1.0)
            assertTrue("actual out of range", b.actual in 0.0..1.0)
            assertTrue("empty bucket reported", b.count > 0)
        }
    }

    @Test
    fun buildsSamplesFromLogsUsingOnlyTheFirstTwoReviews() {
        val logs = mapOf(
            // rating, elapsedDays
            1L to listOf(3 to 0.0, 3 to 4.0, 1 to 9.0),
            2L to listOf(1 to 0.0, 1 to 1.0),
            3L to listOf(3 to 0.0),          // single review: unusable
            4L to listOf(4 to 0.0, 3 to 0.0), // same-day: unusable
        )
        val samples = FsrsOptimizer.samplesFromLogs(logs)

        assertEquals(2, samples.size)
        val good = samples.first { it.firstRating == Rating.GOOD }
        assertEquals(4.0, good.elapsedDays, 1e-9)
        assertTrue("second review rated GOOD should count as recalled", good.recalled)

        val again = samples.first { it.firstRating == Rating.AGAIN }
        assertTrue("second review rated AGAIN is a lapse", !again.recalled)
    }
}
