package com.englus.app.srs

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** One observed outcome: a card reviewed after [elapsedDays], recalled or not. */
data class ReviewSample(
    val firstRating: Rating,
    val elapsedDays: Double,
    val recalled: Boolean,
)

/** Predicted vs observed recall in one band of predicted retrievability. */
data class CalibrationBucket(
    val predictedLow: Double,
    val predictedHigh: Double,
    val count: Int,
    val predicted: Double,
    val actual: Double,
)

data class OptimizationResult(
    /** Fitted w[0..3]: initial stability for AGAIN / HARD / GOOD / EASY. */
    val initialStability: DoubleArray,
    val sampleCount: Int,
    /** Mean log loss before and after, so the change can be judged rather than trusted. */
    val lossBefore: Double,
    val lossAfter: Double,
) {
    val improved: Boolean get() = lossAfter < lossBefore

    override fun equals(other: Any?): Boolean =
        other is OptimizationResult && initialStability.contentEquals(other.initialStability)

    override fun hashCode(): Int = initialStability.contentHashCode()
}

/**
 * Fits the parts of FSRS that one learner's own history can actually determine.
 *
 * The published weights are population averages. A full 19-parameter re-fit needs
 * thousands of reviews and a real optimizer; with a few hundred it would overfit
 * and produce confident nonsense. The four initial-stability weights are
 * different: each is estimated from first reviews of one rating only, it is a
 * one-dimensional fit, and it is the parameter a learner's own pace moves most.
 *
 * Everything else keeps the published value, and the calibration report says
 * plainly whether the model is over- or under-estimating the learner.
 */
object FsrsOptimizer {

    /** Below this a fit is noise dressed as personalisation. */
    const val MIN_SAMPLES_PER_RATING = 12
    const val MIN_TOTAL_SAMPLES = 40

    /**
     * Builds samples from the review log.
     *
     * Only the first review of each card is usable for this fit: after that,
     * stability depends on the whole history and no longer isolates the initial
     * value being estimated.
     */
    fun samplesFromLogs(logsByCard: Map<Long, List<Pair<Int, Double>>>): List<ReviewSample> =
        logsByCard.values.mapNotNull { logs ->
            if (logs.size < 2) return@mapNotNull null
            val (firstRating, _) = logs[0]
            val (secondRating, elapsed) = logs[1]
            if (elapsed <= 0.0) return@mapNotNull null
            ReviewSample(
                firstRating = Rating.of(firstRating),
                elapsedDays = elapsed,
                recalled = secondRating > Rating.AGAIN.value,
            )
        }

    fun optimize(
        samples: List<ReviewSample>,
        baseline: DoubleArray = Fsrs.DEFAULT_WEIGHTS,
    ): OptimizationResult? {
        if (samples.size < MIN_TOTAL_SAMPLES) return null

        val fitted = baseline.copyOf()
        var usable = 0

        Rating.entries.forEach { rating ->
            val forRating = samples.filter { it.firstRating == rating }
            if (forRating.size < MIN_SAMPLES_PER_RATING) return@forEach
            usable += forRating.size
            fitted[rating.value - 1] = fitStability(forRating, baseline[rating.value - 1])
        }
        if (usable < MIN_TOTAL_SAMPLES) return null

        return OptimizationResult(
            initialStability = doubleArrayOf(fitted[0], fitted[1], fitted[2], fitted[3]),
            sampleCount = usable,
            lossBefore = logLoss(samples, baseline),
            lossAfter = logLoss(samples, fitted),
        )
    }

    /**
     * One-dimensional search for the stability that best explains this rating's
     * outcomes. Golden-section over log-stability: the likelihood is smooth and
     * unimodal in S, so a search beats a gradient here and cannot diverge.
     */
    private fun fitStability(samples: List<ReviewSample>, start: Double): Double {
        var lo = ln(MIN_S)
        var hi = ln(MAX_S)
        val phi = (kotlin.math.sqrt(5.0) - 1) / 2

        var c = hi - phi * (hi - lo)
        var d = lo + phi * (hi - lo)
        var fc = loss(samples, kotlin.math.exp(c))
        var fd = loss(samples, kotlin.math.exp(d))

        repeat(60) {
            if (fc < fd) {
                hi = d; d = c; fd = fc
                c = hi - phi * (hi - lo); fc = loss(samples, kotlin.math.exp(c))
            } else {
                lo = c; c = d; fc = fd
                d = lo + phi * (hi - lo); fd = loss(samples, kotlin.math.exp(d))
            }
            if (abs(hi - lo) < 1e-6) return@repeat
        }
        val fitted = kotlin.math.exp((lo + hi) / 2)
        // Refuse a fit that is wildly different from the prior: with limited data
        // that is a sign of a degenerate sample, not a discovery.
        return if (fitted / start > 20 || start / fitted > 20) start else fitted
    }

    private fun loss(samples: List<ReviewSample>, stability: Double): Double {
        var sum = 0.0
        samples.forEach { s ->
            val r = retrievability(s.elapsedDays, stability).coerceIn(1e-6, 1 - 1e-6)
            sum -= if (s.recalled) ln(r) else ln(1 - r)
        }
        return sum / samples.size
    }

    private fun logLoss(samples: List<ReviewSample>, weights: DoubleArray): Double {
        var sum = 0.0
        samples.forEach { s ->
            val stability = weights[s.firstRating.value - 1]
            val r = retrievability(s.elapsedDays, stability).coerceIn(1e-6, 1 - 1e-6)
            sum -= if (s.recalled) ln(r) else ln(1 - r)
        }
        return sum / samples.size
    }

    /** Same curve as [Fsrs]; duplicated here so the fit does not depend on scheduler state. */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + Fsrs.FACTOR * elapsedDays / stability).pow(Fsrs.DECAY)
    }

    /**
     * Predicted versus observed recall, bucketed.
     *
     * This is the honest half of personalisation: even without enough data to
     * re-fit anything, it says whether the schedule is too aggressive or too soft
     * for this learner.
     */
    fun calibration(
        samples: List<ReviewSample>,
        weights: DoubleArray = Fsrs.DEFAULT_WEIGHTS,
        buckets: Int = 5,
    ): List<CalibrationBucket> {
        if (samples.isEmpty()) return emptyList()
        val lowest = 0.5

        return (0 until buckets).mapNotNull { i ->
            val low = lowest + (1.0 - lowest) * i / buckets
            val high = lowest + (1.0 - lowest) * (i + 1) / buckets
            val inBucket = samples.filter { s ->
                val r = retrievability(s.elapsedDays, weights[s.firstRating.value - 1])
                r >= low && (r < high || (i == buckets - 1 && r <= high))
            }
            if (inBucket.isEmpty()) return@mapNotNull null
            CalibrationBucket(
                predictedLow = low,
                predictedHigh = high,
                count = inBucket.size,
                predicted = inBucket.map {
                    retrievability(it.elapsedDays, weights[it.firstRating.value - 1])
                }.average(),
                actual = inBucket.count { it.recalled }.toDouble() / inBucket.size,
            )
        }
    }

    private const val MIN_S = 0.05
    private const val MAX_S = 400.0
}
