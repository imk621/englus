package com.englus.app

import com.englus.app.srs.CardState
import com.englus.app.srs.Fsrs
import com.englus.app.srs.Rating
import com.englus.app.srs.SchedulingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weights here are population averages published with FSRS-5, so these tests
 * check the properties the scheduler must have rather than exact numbers from a
 * reference implementation. Property checks are what actually catch the bugs
 * that matter: a card that gets easier after a lapse, an interval that runs away
 * to infinity, a NaN that silently makes a card never due again.
 */
class FsrsTest {

    private val fsrs = Fsrs()

    // --- retrievability ---

    @Test
    fun retrievabilityIsOneAtZeroElapsed() {
        assertEquals(1.0, fsrs.retrievability(0.0, 10.0), 1e-9)
    }

    @Test
    fun retrievabilityIsRequestRetentionAfterOneStabilityPeriod() {
        // FACTOR is defined so R(S, S) == 0.9. If this drifts, every interval is wrong.
        assertEquals(0.9, fsrs.retrievability(10.0, 10.0), 1e-9)
    }

    @Test
    fun retrievabilityDecaysMonotonically() {
        var previous = 1.1
        listOf(0.0, 1.0, 5.0, 30.0, 365.0).forEach { days ->
            val r = fsrs.retrievability(days, 10.0)
            assertTrue("R rose at $days days", r < previous)
            assertTrue("R out of range at $days days: $r", r in 0.0..1.0)
            previous = r
        }
    }

    // --- first review ---

    @Test
    fun betterFirstRatingGivesLongerInterval() {
        val intervals = Rating.entries.map { fsrs.schedule(it).intervalDays }
        assertEquals("ratings should be ordered", intervals.sorted(), intervals)
        assertTrue("EASY must beat AGAIN", intervals.last() > intervals.first())
    }

    @Test
    fun betterFirstRatingGivesLowerDifficulty() {
        val again = fsrs.schedule(Rating.AGAIN).difficulty
        val easy = fsrs.schedule(Rating.EASY).difficulty
        assertTrue("AGAIN ($again) should be harder than EASY ($easy)", again > easy)
    }

    @Test
    fun firstAgainStartsLearningNotReview() {
        assertEquals(CardState.LEARNING, fsrs.schedule(Rating.AGAIN).state)
        assertEquals(CardState.REVIEW, fsrs.schedule(Rating.GOOD).state)
        assertEquals(1, fsrs.schedule(Rating.AGAIN).lapses)
        assertEquals(0, fsrs.schedule(Rating.GOOD).lapses)
    }

    // --- subsequent reviews ---

    @Test
    fun goodReviewsGrowTheInterval() {
        var state = fsrs.schedule(Rating.GOOD)
        var previous = state.intervalDays
        repeat(6) {
            state = fsrs.schedule(state, state.intervalDays.toDouble(), Rating.GOOD)
            assertTrue("interval shrank: $previous -> ${state.intervalDays}", state.intervalDays >= previous)
            previous = state.intervalDays
        }
        assertTrue("six good reviews should reach weeks, got $previous", previous > 20)
    }

    @Test
    fun aLapseNeverLengthensTheInterval() {
        var state = fsrs.schedule(Rating.GOOD)
        repeat(5) { state = fsrs.schedule(state, state.intervalDays.toDouble(), Rating.GOOD) }

        val before = state.intervalDays
        val after = fsrs.schedule(state, state.intervalDays.toDouble(), Rating.AGAIN)
        assertTrue("lapse grew the interval: $before -> ${after.intervalDays}", after.intervalDays <= before)
        assertEquals(CardState.RELEARNING, after.state)
        assertEquals(state.lapses + 1, after.lapses)
    }

    @Test
    fun lapsesMakeCardsHarderAndEasyMakesThemEasier() {
        var state = fsrs.schedule(Rating.GOOD)
        val start = state.difficulty

        var hard = state
        repeat(3) { hard = fsrs.schedule(hard, 1.0, Rating.AGAIN) }
        assertTrue("difficulty did not rise after lapses", hard.difficulty > start)

        var easy = state
        repeat(3) { easy = fsrs.schedule(easy, 1.0, Rating.EASY) }
        assertTrue("difficulty did not fall after easy reviews", easy.difficulty < start)
    }

    @Test
    fun difficultyStaysInRangeUnderAbuse() {
        var state = fsrs.schedule(Rating.AGAIN)
        repeat(50) { state = fsrs.schedule(state, 0.5, Rating.AGAIN) }
        assertTrue("difficulty escaped: ${state.difficulty}", state.difficulty in 1.0..10.0)

        var easy = fsrs.schedule(Rating.EASY)
        repeat(50) { easy = fsrs.schedule(easy, 100.0, Rating.EASY) }
        assertTrue("difficulty escaped: ${easy.difficulty}", easy.difficulty in 1.0..10.0)
    }

    @Test
    fun aSameDayRepeatIsWorthLessThanWaiting() {
        // The property that matters: re-reading a card immediately must not be
        // worth as much as recalling it after the interval elapsed. FSRS does let
        // same-day reviews raise stability — via the short-term formula — but by
        // much less than a spaced recall, which is what keeps cramming from
        // buying long intervals.
        val state = fsrs.schedule(Rating.GOOD)

        val sameDay = fsrs.schedule(state, 0.0, Rating.GOOD)
        val spaced = fsrs.schedule(state, state.intervalDays.toDouble(), Rating.GOOD)

        assertTrue(
            "cramming (${sameDay.stability}) should be worth less than spacing (${spaced.stability})",
            sameDay.stability < spaced.stability,
        )
    }

    @Test
    fun sameDayRatingsStayOrdered() {
        val state = fsrs.schedule(Rating.GOOD)
        val byRating = Rating.entries.map { fsrs.schedule(state, 0.0, it).stability }
        assertEquals("same-day ratings should be ordered", byRating.sorted(), byRating)
    }

    @Test
    fun intervalsAreAlwaysAtLeastOneDayAndBounded() {
        val states = mutableListOf<SchedulingState>()
        Rating.entries.forEach { r ->
            var s = fsrs.schedule(r)
            repeat(30) { s = fsrs.schedule(s, s.intervalDays.toDouble(), Rating.entries.random()) }
            states += s
        }
        states.forEach {
            assertTrue("interval ${it.intervalDays} out of bounds", it.intervalDays in 1..(365 * 5))
            assertTrue("stability went non-finite", it.stability.isFinite())
            assertTrue("difficulty went non-finite", it.difficulty.isFinite())
        }
    }

    @Test
    fun aLongOverdueCardIsNotPunishedForBeingRemembered() {
        // Recalling something long overdue is strong evidence; stability must rise.
        var state = fsrs.schedule(Rating.GOOD)
        state = fsrs.schedule(state, state.intervalDays.toDouble(), Rating.GOOD)

        val onTime = fsrs.schedule(state, state.intervalDays.toDouble(), Rating.GOOD)
        val overdue = fsrs.schedule(state, state.intervalDays * 5.0, Rating.GOOD)
        assertTrue(
            "overdue recall should not be worth less than on-time",
            overdue.stability >= onTime.stability,
        )
    }

    @Test
    fun elapsedDaysConvertsFromTimestamps() {
        val now = 1_000L * Fsrs.DAY_MS
        assertEquals(0.0, Fsrs.elapsedDays(0L, now), 1e-9)
        assertEquals(3.0, Fsrs.elapsedDays(now - 3 * Fsrs.DAY_MS, now), 1e-9)
    }
}
