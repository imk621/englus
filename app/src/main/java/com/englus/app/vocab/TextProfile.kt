package com.englus.app.vocab

/** Frequency band a word falls in. The bands are the standard K-bands of vocabulary research. */
enum class Band(val label: String, val words: Int) {
    K1("1-1000", 1000),
    K2("1001-2000", 1000),
    K3("2001-3000", 1000),
    K5("3001-5000", 2000),
    K10("5001-10000", 5000),
    /** Beyond the list: proper nouns, technical terms, and genuinely rare words. */
    OFF("10000+", 0);

    companion object {
        fun of(rank: Int?): Band = when {
            rank == null -> OFF
            rank <= 1000 -> K1
            rank <= 2000 -> K2
            rank <= 3000 -> K3
            rank <= 5000 -> K5
            rank <= 10000 -> K10
            else -> OFF
        }
    }
}

/**
 * Rough CEFR band.
 *
 * There is no A1: with a 10,000-word list and coverage alone, A1 and A2 are not
 * separable, and inventing the distinction would be the kind of precision that
 * reads as authoritative while meaning nothing.
 */
enum class Cefr(val label: String, val blurb: String) {
    A2("A2", "쉬운 영어. 통제된 어휘로 쓰인 학습용 자료 수준입니다."),
    B1("B1", "일상 영어. 아동 문학이나 쉬운 이야기 수준입니다."),
    B2("B2", "일반 영어. 라디오 대담이나 대중 기사 수준입니다."),
    C1("C1", "문학·시사 영어. 원문 소설이나 심층 기사 수준입니다."),
    C2("C2", "전문·학술 영어. 논문이나 고전 산문 수준입니다.");
}

data class TextProfile(
    val tokenCount: Int,
    val distinctLemmas: Int,
    /** Token counts per band — how much of the *running text* each band covers. */
    val bandCounts: Map<Band, Int>,
    val k1Coverage: Float,
    val k2Coverage: Float,
    val offListRate: Float,
    val avgSentenceLength: Float,
    val cefr: Cefr,
) {
    fun coverage(band: Band): Float =
        if (tokenCount == 0) 0f else (bandCounts[band] ?: 0).toFloat() / tokenCount
}

/**
 * Estimates how hard a text is from the frequency bands its words fall into.
 *
 * Coverage by the most common 2,000 words is the measure that carries the signal.
 * Readability formulas built on syllable counts were designed for English-speaking
 * schoolchildren and mostly detect long words; for a second-language learner the
 * question is simply how many words they will not know, and that is what band
 * coverage measures directly.
 *
 * The thresholds are not invented. They were fitted to six texts of known level,
 * including the same story in a simplified and an original edition — the case that
 * would expose a metric measuring style rather than difficulty:
 *
 *   VOA Learning English, "Usher" simplified   k2 .896  ->  A2
 *   Alice in Wonderland                        k2 .873  ->  B1
 *   BBC 6 Minute English transcript            k2 .810  ->  B2
 *   Frankenstein                               k2 .770  ->  C1
 *   Poe, "Usher" original                      k2 .766  ->  C1
 *   Darwin, Origin of Species                  k2 .684  ->  C2
 *
 * The simplified and original Usher differ by .130 — the metric separates them
 * cleanly, which is the whole point.
 */
object DifficultyEstimator {

    fun profile(text: String, lemmatizer: Lemmatizer, ranks: WordRanks): TextProfile =
        profile(listOf(text), lemmatizer, ranks)

    fun profile(
        sentences: List<String>,
        lemmatizer: Lemmatizer,
        ranks: WordRanks,
    ): TextProfile {
        val counts = mutableMapOf<Band, Int>()
        val distinct = mutableSetOf<String>()
        var total = 0
        var k1 = 0
        var k2 = 0
        var off = 0

        sentences.forEach { sentence ->
            Lemmatizer.tokenize(sentence).forEach { token ->
                val lemma = lemmatizer.lemma(token)
                val rank = ranks.rank(lemma)
                val band = Band.of(rank)
                counts[band] = (counts[band] ?: 0) + 1
                distinct += lemma
                total++
                if (rank != null) {
                    if (rank <= 1000) k1++
                    if (rank <= 2000) k2++
                } else {
                    off++
                }
            }
        }

        if (total == 0) {
            return TextProfile(0, 0, emptyMap(), 0f, 0f, 0f, 0f, Cefr.B1)
        }

        // Sentence count from the caller's split when there is one, otherwise
        // from terminal punctuation.
        val sentenceCount = if (sentences.size > 1) sentences.size
        else sentences.firstOrNull()?.split(Regex("[.!?]+"))
            ?.count { it.trim().split(" ").size > 2 }?.coerceAtLeast(1) ?: 1

        val k2Coverage = k2.toFloat() / total
        val avgLen = total.toFloat() / sentenceCount

        return TextProfile(
            tokenCount = total,
            distinctLemmas = distinct.size,
            bandCounts = counts,
            k1Coverage = k1.toFloat() / total,
            k2Coverage = k2Coverage,
            offListRate = off.toFloat() / total,
            avgSentenceLength = avgLen,
            cefr = cefr(k2Coverage, avgLen),
        )
    }

    /**
     * Sentence length adjusts the vocabulary score rather than deciding on its
     * own. It barely moved across the calibration texts (19-21 words for four of
     * the six), so it is a weak signal — but for listening it is a real one:
     * a long sentence has to be held in memory whole. Capped at +/-.03, about
     * half a band, so it can nudge a borderline text and never overturn one.
     */
    internal fun cefr(k2Coverage: Float, avgSentenceLength: Float): Cefr {
        val adjusted = k2Coverage + ((16f - avgSentenceLength) * 0.004f).coerceIn(-0.03f, 0.03f)
        return when {
            adjusted >= 0.875f -> Cefr.A2
            adjusted >= 0.84f -> Cefr.B1
            adjusted >= 0.78f -> Cefr.B2
            adjusted >= 0.70f -> Cefr.C1
            else -> Cefr.C2
        }
    }
}
