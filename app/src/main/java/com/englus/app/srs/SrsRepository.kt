package com.englus.app.srs

import android.content.Context
import com.englus.app.data.DueCard
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.ReviewCard
import com.englus.app.data.ReviewLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar

class SrsRepository(context: Context) {

    private val dao = EnglusDatabase.get(context).reviews()
    private val fsrs = Fsrs()

    /** Creates cards for saved sentences that lack one. Safe to call repeatedly. */
    suspend fun ensureCards() = withContext(Dispatchers.IO) {
        dao.backfill(System.currentTimeMillis())
    }

    /** Creates missing cards, then returns what is due now. */
    suspend fun loadDue(): List<DueCard> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.backfill(now)
        dao.due(now)
    }

    fun observeDueCount(): Flow<Int> = dao.observeDueCount(System.currentTimeMillis())
    fun observeTotalCards(): Flow<Int> = dao.observeTotalCards()
    fun observeReviewsToday(): Flow<Int> = dao.observeReviewsSince(startOfToday())

    /** What each button would schedule, so the choice is not blind. */
    fun preview(card: ReviewCard): Map<Rating, Int> {
        val elapsed = Fsrs.elapsedDays(card.lastReview, System.currentTimeMillis())
        return Rating.entries.associateWith { rating -> next(card, elapsed, rating).intervalDays }
    }

    suspend fun grade(card: ReviewCard, rating: Rating) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val elapsed = Fsrs.elapsedDays(card.lastReview, now)
        val next = next(card, elapsed, rating)

        dao.update(
            card.copy(
                stability = next.stability,
                difficulty = next.difficulty,
                state = next.state,
                reps = next.reps,
                lapses = next.lapses,
                due = now + next.intervalDays * Fsrs.DAY_MS,
                lastReview = now,
            )
        )
        // Logged before anything can fail downstream: the log is what a future
        // re-fit of the FSRS weights is trained on, and it cannot be rebuilt.
        dao.log(
            ReviewLog(
                cardId = card.id,
                rating = rating.value,
                stateBefore = card.state,
                elapsedDays = elapsed,
                scheduledDays = next.intervalDays,
                reviewedAt = now,
            )
        )
    }

    private fun next(card: ReviewCard, elapsed: Double, rating: Rating): SchedulingState =
        if (card.state == CardState.NEW) {
            fsrs.schedule(rating)
        } else {
            fsrs.schedule(
                SchedulingState(
                    stability = card.stability,
                    difficulty = card.difficulty,
                    state = card.state,
                    reps = card.reps,
                    lapses = card.lapses,
                    intervalDays = 0,
                ),
                elapsed,
                rating,
            )
        }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
