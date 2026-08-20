package com.englus.app.dictation

enum class DiffStatus {
    /** Heard and typed correctly. */
    MATCH,

    /** Typed something else in this position. */
    WRONG,

    /** In the audio, absent from the answer. */
    MISSING,

    /** In the answer, not in the audio. */
    EXTRA,
}

data class DiffToken(val text: String, val status: DiffStatus)

data class DiffResult(
    /** The real sentence, each word marked. */
    val reference: List<DiffToken>,
    /** What the learner typed, each word marked. */
    val hypothesis: List<DiffToken>,
    val correctWords: Int,
    val totalWords: Int,
) {
    val accuracy: Float get() = if (totalWords == 0) 0f else correctWords.toFloat() / totalWords
    val isPerfect: Boolean get() = correctWords == totalWords && hypothesis.none { it.status == DiffStatus.EXTRA }
}

/**
 * Word-level alignment between the transcript and what the learner typed.
 *
 * Scoring position by position would punish a single dropped article by marking
 * every later word wrong, which is both wrong and demoralising. Alignment finds
 * the actual insertions and deletions, so "the" being missed costs one word.
 *
 * Comparison is on a normalised form (case and punctuation dropped, apostrophes
 * kept because "dont" is not "don't"), while the tokens shown keep their original
 * spelling.
 */
object WordDiff {

    fun compare(reference: String, hypothesis: String): DiffResult {
        val refTokens = tokenize(reference)
        val hypTokens = tokenize(hypothesis)
        val refNorm = refTokens.map(::normalize)
        val hypNorm = hypTokens.map(::normalize)

        val n = refTokens.size
        val m = hypTokens.size

        if (n == 0) {
            return DiffResult(
                reference = emptyList(),
                hypothesis = hypTokens.map { DiffToken(it, DiffStatus.EXTRA) },
                correctWords = 0,
                totalWords = 0,
            )
        }

        // Standard edit-distance table; MATCH is free, the three edits cost 1.
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                cost[i][j] = if (refNorm[i - 1] == hypNorm[j - 1]) {
                    cost[i - 1][j - 1]
                } else {
                    minOf(
                        cost[i - 1][j - 1] + 1,  // substitution
                        cost[i][j - 1] + 1,      // insertion (extra word typed)
                        cost[i - 1][j] + 1,      // deletion (word missed)
                    )
                }
            }
        }

        val refOut = ArrayDeque<DiffToken>()
        val hypOut = ArrayDeque<DiffToken>()
        var correct = 0
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val matched = i > 0 && j > 0 && refNorm[i - 1] == hypNorm[j - 1]
            when {
                matched && cost[i][j] == cost[i - 1][j - 1] -> {
                    refOut.addFirst(DiffToken(refTokens[i - 1], DiffStatus.MATCH))
                    hypOut.addFirst(DiffToken(hypTokens[j - 1], DiffStatus.MATCH))
                    correct++
                    i--; j--
                }
                i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + 1 -> {
                    refOut.addFirst(DiffToken(refTokens[i - 1], DiffStatus.WRONG))
                    hypOut.addFirst(DiffToken(hypTokens[j - 1], DiffStatus.WRONG))
                    i--; j--
                }
                j > 0 && cost[i][j] == cost[i][j - 1] + 1 -> {
                    hypOut.addFirst(DiffToken(hypTokens[j - 1], DiffStatus.EXTRA))
                    j--
                }
                else -> {
                    refOut.addFirst(DiffToken(refTokens[i - 1], DiffStatus.MISSING))
                    i--
                }
            }
        }

        return DiffResult(
            reference = refOut.toList(),
            hypothesis = hypOut.toList(),
            correctWords = correct,
            totalWords = n,
        )
    }

    private fun tokenize(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Keeps apostrophes and digits, drops everything else. Punctuation is not
     * something you can hear, so scoring it would measure typing, not listening.
     */
    internal fun normalize(token: String): String =
        token.lowercase()
            .replace('’', '\'')   // curly apostrophe from transcripts
            .filter { it.isLetterOrDigit() || it == '\'' }
            .trim('\'')
}
