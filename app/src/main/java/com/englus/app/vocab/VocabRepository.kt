package com.englus.app.vocab

import android.content.Context
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.VocabBandStat
import com.englus.app.data.VocabState
import com.englus.app.data.SavedSentence
import com.englus.app.data.VocabWord
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.DiffStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * How much of a text this learner is expected not to know.
 *
 * The absolute CEFR band says what the text is; this says what it is to them,
 * which is the number that decides whether an episode is worth listening to.
 */
data class UnknownRate(
    val rate: Float,
    val unknownTokens: Int,
    val totalTokens: Int,
    /** Distinct unknown lemmas, most frequent first. */
    val words: List<String>,
) {
    /**
     * Thresholds from vocabulary-coverage research: about 98% known text is what
     * unassisted listening needs, and below roughly 90% comprehension collapses
     * whatever the effort.
     */
    val verdict: String
        get() = when {
            rate <= 0.02f -> "편하게 들을 수 있습니다"
            rate <= 0.05f -> "적당합니다 - 새 단어를 배우기 좋은 밀도입니다"
            rate <= 0.10f -> "어렵습니다 - 정독하듯 끊어서 들으세요"
            else -> "지금은 너무 어렵습니다"
        }
}

class VocabRepository(private val context: Context) {

    private val dao = EnglusDatabase.get(context).vocab()
    private val episodes = EnglusDatabase.get(context).episodes()

    private val ranks: FrequencyList by lazy { FrequencyList.get(context) }
    private val lemmatizer: Lemmatizer by lazy { Lemmatizer(ranks) }

    fun observeBandStats() = dao.observeBandStats()
    fun observeUnknown() = dao.observeUnknown()
    fun observeKnownCount() = dao.observeKnownCount()
    fun observeMetCount() = dao.observeMetCount()

    /**
     * Records what a scored attempt revealed.
     *
     * Only scored activity counts. Having a word pass in front of your eyes is
     * not evidence you know it, and counting exposure would fill the profile
     * with words the learner has never once produced or recognised.
     *
     * [correctOnly] is for shadowing. There the comparison runs against speech
     * recognition, so a mismatch can mean the word was unknown, mispronounced,
     * or simply misheard by the recogniser - three different things. Only the
     * matches are trustworthy, so only they are recorded.
     */
    suspend fun ingest(diff: DiffResult, correctOnly: Boolean = false) = withContext(Dispatchers.IO) {
        val correct = mutableSetOf<String>()
        val missed = mutableSetOf<String>()

        diff.reference.forEach { token ->
            val lemma = lemmatizer.lemma(token.text.filter { it.isLetter() || it == APOSTROPHE })
            if (lemma.length < 2) return@forEach
            when (token.status) {
                DiffStatus.MATCH -> correct += lemma
                DiffStatus.WRONG, DiffStatus.MISSING -> missed += lemma
                DiffStatus.EXTRA -> Unit
            }
        }
        // A word both matched and missed inside one sentence is not evidence of
        // knowing it.
        correct -= missed
        if (correctOnly) missed.clear()
        if (correct.isEmpty() && missed.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        val touched = (correct + missed).toList()
        val existing = dao.get(touched).associateBy { it.lemma }

        dao.upsert(
            touched.map { lemma ->
                val prior = existing[lemma]
                val isCorrect = lemma in correct
                val ok = (prior?.correct ?: 0) + if (isCorrect) 1 else 0
                val bad = (prior?.missed ?: 0) + if (isCorrect) 0 else 1
                val manual = prior?.manual ?: 0
                VocabWord(
                    lemma = lemma,
                    rank = ranks.rank(lemma) ?: 0,
                    seen = (prior?.seen ?: 0) + 1,
                    correct = ok,
                    missed = bad,
                    state = stateFor(ok, bad, manual),
                    manual = manual,
                    firstSeenAt = prior?.firstSeenAt ?: now,
                    lastSeenAt = now,
                )
            }
        )
    }

    /**
     * The sentences this word was actually met in.
     *
     * The point of the vocabulary profile is to send the learner back to their
     * own material, not to a word list. A word met again in the sentence and the
     * voice that defeated it the first time is being learned where it will next
     * be needed; the same word on a flashcard is being learned nowhere.
     *
     * Matching is done here rather than in SQL because only here can inflections
     * be resolved: the query casts a wide net with a stem, and every candidate
     * is then checked token by token against the lemma.
     */
    suspend fun sentencesFor(lemma: String, limit: Int = 12): List<SavedSentence> =
        withContext(Dispatchers.IO) {
            val candidates = dao.sentencesContaining("%${searchStem(lemma)}%")
            withContext(Dispatchers.Default) {
                candidates
                    .filter { row ->
                        Lemmatizer.tokenize(row.sentence.text).any { lemmatizer.lemma(it) == lemma }
                    }
                    .take(limit)
            }
        }

    suspend fun word(lemma: String) = withContext(Dispatchers.IO) { dao.word(lemma) }

    suspend fun setKnown(lemma: String, known: Boolean) = withContext(Dispatchers.IO) {
        dao.setManual(
            lemma,
            if (known) VocabState.KNOWN else VocabState.UNKNOWN,
            if (known) 1 else 2,
        )
    }

    /** The difficulty of the text itself, independent of who is reading it. */
    suspend fun profile(sentences: List<String>): TextProfile = withContext(Dispatchers.Default) {
        DifficultyEstimator.profile(sentences, lemmatizer, ranks)
    }

    /**
     * What this learner is likely not to know in a text.
     *
     * Anything the profile has not judged KNOWN counts as unknown, which
     * overstates the rate early on when the profile is nearly empty. That
     * direction is the safe one - it recommends easier material to someone the
     * app does not know yet - and the screen says how much evidence it has.
     */
    suspend fun unknownRate(sentences: List<String>): UnknownRate = withContext(Dispatchers.IO) {
        val known = dao.knownLemmas().toHashSet()
        var total = 0
        var unknown = 0
        val words = LinkedHashMap<String, Int>()
        sentences.forEach { s ->
            Lemmatizer.tokenize(s).forEach { token ->
                val lemma = lemmatizer.lemma(token)
                total++
                if (lemma !in known) {
                    unknown++
                    words[lemma] = ranks.rank(lemma) ?: Int.MAX_VALUE
                }
            }
        }
        UnknownRate(
            rate = if (total == 0) 0f else unknown.toFloat() / total,
            unknownTokens = unknown,
            totalTokens = total,
            words = words.entries.sortedBy { it.value }.map { it.key },
        )
    }

    /**
     * Measures an episode once its text exists and stores the result.
     *
     * Stored rather than recomputed on every list render: the library shows a
     * badge per episode, and profiling a 40-minute transcript is thousands of
     * lemma lookups.
     */
    suspend fun measureEpisode(episodeId: Long) = withContext(Dispatchers.IO) {
        val episode = episodes.get(episodeId) ?: return@withContext
        val sentences = episodes.observeSentences(episodeId).first().map { it.text }
        if (sentences.isEmpty()) return@withContext
        val p = profile(sentences)
        if (p.tokenCount < MIN_TOKENS_FOR_ESTIMATE) return@withContext
        episodes.update(
            episode.copy(
                cefr = p.cefr.name,
                k2Coverage = p.k2Coverage,
                avgSentenceLen = p.avgSentenceLength,
            )
        )
    }

    companion object {
        /** Kept as a named constant so the escape does not sit in the middle of a filter. */
        private const val APOSTROPHE = '\''

        /**
         * Below this the coverage percentages swing wildly on a single proper
         * noun, so no estimate is better than a confident wrong one.
         */
        /**
         * Evidence to verdict.
         *
         * Two clean hits before a word counts as known: one is as likely to be a
         * guess, or a word so predictable from context that it was never really
         * heard. A hand judgement wins outright, because the learner knows things
         * the app has had no chance to observe.
         */
        fun stateFor(correct: Int, missed: Int, manual: Int): VocabState = when {
            manual == 1 -> VocabState.KNOWN
            manual == 2 -> VocabState.UNKNOWN
            missed > 0 && correct == 0 -> VocabState.UNKNOWN
            correct >= 2 && correct > missed -> VocabState.KNOWN
            else -> VocabState.LEARNING
        }

        const val MIN_TOKENS_FOR_ESTIMATE = 150

        /** A band needs this many met words before its known ratio means anything. */
        const val MIN_MET_PER_BAND = 15

        /**
         * The prefix to hand SQL, wide enough to survive spelling changes.
         *
         * A final "y" becomes "ie" before an ending ("study" -> "studies") and a
         * final "e" is dropped before one ("hope" -> "hoping"), so searching for
         * the lemma itself would miss exactly the forms most likely to appear.
         * Every other inflection keeps the lemma as a prefix.
         */
        fun searchStem(lemma: String): String =
            if (lemma.length > 3 && (lemma.endsWith("y") || lemma.endsWith("e"))) {
                lemma.dropLast(1)
            } else {
                lemma
            }

        /**
         * Extrapolates a vocabulary size from the bands with enough evidence.
         *
         * A lower bound, not a test score, and the screen says so. The words the
         * app has met are the ones that turned up in sentences the learner chose
         * to drill, which skews hard, so the true figure is higher. It is still
         * the right shape for tracking progress, because the bias is roughly
         * constant over time.
         */
        fun estimateSize(stats: List<VocabBandStat>): Int? {
            val sizes = listOf(1000, 1000, 1000, 2000, 5000)
            var total = 0.0
            var covered = false
            stats.forEach { s ->
                if (s.band !in sizes.indices || s.met < MIN_MET_PER_BAND) return@forEach
                covered = true
                total += sizes[s.band] * (s.known.toDouble() / s.met)
            }
            return if (covered) total.toInt() else null
        }
    }
}
