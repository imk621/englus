package com.englus.app.level

import com.englus.app.vocab.Band
import com.englus.app.vocab.Cefr
import com.englus.app.vocab.WordRanks
import kotlin.random.Random

/** One item put to the learner. A control item is not an English word at all. */
data class LevelItem(
    val word: String,
    /** 0..4 for the K-bands in order; null for a control item. */
    val band: Int?,
) {
    val isControl: Boolean get() = band == null
}

data class BandScore(
    val band: Int,
    val asked: Int,
    val claimed: Int,
    /** Claim rate after the overclaiming seen on control items is removed. */
    val corrected: Float,
)

data class LevelResult(
    val vocabularySize: Int,
    val cefr: Cefr,
    val bands: List<BandScore>,
    /** How often a word that does not exist was claimed, 0..1. */
    val falseAlarmRate: Float,
    val takenAt: Long,
) {
    /**
     * Above this the corrected score is mostly noise: someone saying yes to
     * nearly everything leaves too little signal to divide by.
     */
    val unreliable: Boolean get() = falseAlarmRate > 0.5f
}

/**
 * A vocabulary size estimate in a few minutes, on the first day.
 *
 * The app already measures vocabulary, but only as a by-product of dictation -
 * which means it says nothing until weeks of work have accumulated, and it only
 * ever sees words that happened to appear in the material. A learner opening the
 * app for the first time has no level, so nothing can be recommended to them.
 *
 * The method is the standard yes/no test, and its one essential feature is the
 * control items. Asked whether they know a word, people say yes to things they
 * have merely seen, and the inflation is large enough to move the answer by a
 * band or two. Mixing in words that do not exist measures that inflation
 * directly: whatever rate someone claims those at is the rate at which their
 * other answers are wrong too, and the score is corrected by it.
 *
 * That correction is the whole reason to prefer this over simply asking.
 */
object LevelTest {

    /** Words per band. Enough to be stable, few enough to finish in a sitting. */
    const val PER_BAND = 8
    const val CONTROLS = 10

    /** Band sizes, matching [Band]: K1, K2, K3, K3-5, K5-10. */
    private val BAND_SIZES = listOf(1000, 1000, 1000, 2000, 5000)
    private val BAND_RANGES = listOf(1..1000, 1001..2000, 2001..3000, 3001..5000, 5001..10000)

    fun build(ranks: WordRanks, nonWords: List<String>, random: Random = Random.Default): List<LevelItem> {
        val items = mutableListOf<LevelItem>()

        BAND_RANGES.forEachIndexed { band, range ->
            // Sampled fresh each time, so a retake is not the same test learned.
            val picked = mutableSetOf<String>()
            var guard = 0
            while (picked.size < PER_BAND && guard++ < 500) {
                val rank = random.nextInt(range.first, range.last + 1)
                wordAt(ranks, rank)?.let { picked += it }
            }
            picked.forEach { items += LevelItem(it, band) }
        }

        nonWords.shuffled(random).take(CONTROLS).forEach { items += LevelItem(it, null) }
        return items.shuffled(random)
    }

    /**
     * Scores the answers.
     *
     * The correction is the standard one: a claim rate of h against a false
     * alarm rate of f is worth (h - f) / (1 - f). At f = 0 it changes nothing;
     * as f rises it discounts every band by how freely this person says yes.
     */
    fun score(items: List<LevelItem>, claimed: Set<String>, now: Long): LevelResult {
        val controls = items.filter { it.isControl }
        val falseAlarm = if (controls.isEmpty()) 0f
        else controls.count { it.word in claimed }.toFloat() / controls.size

        val bands = BAND_SIZES.indices.map { band ->
            val asked = items.filter { it.band == band }
            val hits = asked.count { it.word in claimed }
            val rate = if (asked.isEmpty()) 0f else hits.toFloat() / asked.size
            BandScore(
                band = band,
                asked = asked.size,
                claimed = hits,
                corrected = correct(rate, falseAlarm),
            )
        }

        val size = bands.sumOf { (BAND_SIZES[it.band] * it.corrected).toDouble() }.toInt()
        return LevelResult(
            vocabularySize = size,
            cefr = cefrForSize(size),
            bands = bands,
            falseAlarmRate = falseAlarm,
            takenAt = now,
        )
    }

    internal fun correct(hitRate: Float, falseAlarm: Float): Float {
        if (falseAlarm <= 0f) return hitRate.coerceIn(0f, 1f)
        if (falseAlarm >= 1f) return 0f
        return ((hitRate - falseAlarm) / (1f - falseAlarm)).coerceIn(0f, 1f)
    }

    /**
     * Vocabulary size to a band.
     *
     * The thresholds are the ones vocabulary research uses for text coverage:
     * about 2,000 words carries everyday speech, 3,000 is the floor for
     * unsimplified material, and 8,000 is where ordinary native text stops
     * needing help. Deliberately coarse - a single sitting cannot support
     * finer than this.
     */
    internal fun cefrForSize(size: Int): Cefr = when {
        size < 1500 -> Cefr.A2
        size < 2500 -> Cefr.B1
        size < 4000 -> Cefr.B2
        size < 8000 -> Cefr.C1
        else -> Cefr.C2
    }

    private fun wordAt(ranks: WordRanks, rank: Int): String? = ranks.wordAt(rank)
}
