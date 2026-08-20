package com.englus.app.srs

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** How the learner rated a card. Matches FSRS grades 1..4. */
enum class Rating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        fun of(v: Int): Rating = entries.firstOrNull { it.value == v } ?: GOOD
    }
}

enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }

/**
 * Scheduling state for one card. Days are used throughout because that is the
 * unit FSRS is defined in; storage keeps absolute timestamps.
 */
data class SchedulingState(
    val stability: Double,
    val difficulty: Double,
    val state: CardState,
    val reps: Int,
    val lapses: Int,
    /** Days until this card should come back. */
    val intervalDays: Int,
)

/**
 * FSRS-5.
 *
 * Chosen over SM-2 because it reaches the same retention with materially fewer
 * reviews — which matters when every card is a sentence you failed and the deck
 * only grows. SM-2 treats all lapses alike; FSRS models difficulty and
 * forgetting separately, so a sentence you keep missing comes back fast while
 * one you nailed recedes properly.
 *
 * The weights are the published FSRS-5 defaults. They are population averages:
 * re-fitting them to one learner's own review log is a later step, which is why
 * every review is recorded.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_WEIGHTS,
    /** Target probability of recall when the card comes back. */
    private val requestRetention: Double = 0.9,
    private val maximumIntervalDays: Int = 365 * 5,
) {

    /** Probability the card is still remembered after [elapsedDays]. */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    /** First review of a brand-new card. */
    fun schedule(rating: Rating): SchedulingState {
        val stability = initialStability(rating)
        val difficulty = initialDifficulty(rating)
        return finish(
            stability = stability,
            difficulty = difficulty,
            state = if (rating == Rating.AGAIN) CardState.LEARNING else CardState.REVIEW,
            reps = 1,
            lapses = if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** A review of a card that already has scheduling state. */
    fun schedule(previous: SchedulingState, elapsedDays: Double, rating: Rating): SchedulingState {
        if (previous.state == CardState.NEW) return schedule(rating)

        val r = retrievability(elapsedDays.coerceAtLeast(0.0), previous.stability)
        val difficulty = nextDifficulty(previous.difficulty, rating)

        // Same-day repeats do not "earn" a full interval; FSRS models them with a
        // short-term formula so hammering a card repeatedly cannot inflate it.
        val stability = when {
            elapsedDays < 1.0 -> shortTermStability(previous.stability, rating)
            rating == Rating.AGAIN -> lapseStability(previous.stability, difficulty, r)
            else -> recallStability(previous.stability, difficulty, r, rating)
        }

        return finish(
            stability = stability,
            difficulty = difficulty,
            state = if (rating == Rating.AGAIN) CardState.RELEARNING else CardState.REVIEW,
            reps = previous.reps + 1,
            lapses = previous.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    private fun finish(
        stability: Double,
        difficulty: Double,
        state: CardState,
        reps: Int,
        lapses: Int,
    ): SchedulingState {
        val s = stability.coerceIn(MIN_STABILITY, maximumIntervalDays.toDouble())
        val d = difficulty.coerceIn(1.0, 10.0)
        return SchedulingState(
            stability = s,
            difficulty = d,
            state = state,
            reps = reps,
            lapses = lapses,
            intervalDays = intervalFor(s),
        )
    }

    /** Days at which retrievability decays to [requestRetention]. */
    fun intervalFor(stability: Double): Int {
        val days = (stability / FACTOR) * (requestRetention.pow(1.0 / DECAY) - 1.0)
        return days.coerceIn(1.0, maximumIntervalDays.toDouble()).toInt()
    }

    private fun initialStability(rating: Rating): Double =
        w[rating.value - 1].coerceAtLeast(MIN_STABILITY)

    private fun initialDifficulty(rating: Rating): Double =
        (w[4] - exp(w[5] * (rating.value - 1)) + 1.0).coerceIn(1.0, 10.0)

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -w[6] * (rating.value - 3)
        // Linear damping: the closer difficulty is to the ceiling, the less a
        // single bad review moves it. Without this a couple of lapses pin a card
        // at 10 forever.
        val damped = difficulty + delta * ((10.0 - difficulty) / 9.0)
        val meanReverted = w[7] * initialDifficulty(Rating.EASY) + (1.0 - w[7]) * damped
        return meanReverted.coerceIn(1.0, 10.0)
    }

    private fun recallStability(
        stability: Double,
        difficulty: Double,
        r: Double,
        rating: Rating,
    ): Double {
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
        return stability * (
            1.0 + exp(w[8]) *
                (11.0 - difficulty) *
                stability.pow(-w[9]) *
                (exp(w[10] * (1.0 - r)) - 1.0) *
                hardPenalty *
                easyBonus
            )
    }

    private fun lapseStability(stability: Double, difficulty: Double, r: Double): Double {
        val postLapse = w[11] *
            difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - r))
        // A lapse must never lengthen the interval.
        return minOf(postLapse, stability)
    }

    private fun shortTermStability(stability: Double, rating: Rating): Double =
        stability * exp(w[17] * (rating.value - 3 + w[18]))

    companion object {
        /** FSRS-5 published defaults. */
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046,
            1.54575, 0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315,
            2.9898, 0.51655, 0.6621,
        )

        const val DECAY = -0.5
        /** Derived so that R(S, S) == 0.9 exactly. */
        val FACTOR: Double = 0.9.pow(1.0 / DECAY) - 1.0

        const val MIN_STABILITY = 0.01

        const val DAY_MS = 24L * 60 * 60 * 1000

        fun elapsedDays(lastReviewMs: Long, nowMs: Long): Double =
            if (lastReviewMs <= 0) 0.0 else (nowMs - lastReviewMs).toDouble() / DAY_MS

        /** Only used by the parameter-fitting step that is not built yet. */
        fun logRetention(r: Double): Double = ln(r.coerceIn(1e-9, 1.0))
    }
}
