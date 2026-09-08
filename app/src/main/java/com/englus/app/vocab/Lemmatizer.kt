package com.englus.app.vocab

/** Rank of a word in a frequency list: 1 is the most common, null means off-list. */
interface WordRanks {
    fun rank(word: String): Int?

    /** How many words the list holds - the ceiling on any coverage claim. */
    val size: Int

    /** The word at a rank, for sampling a band. Null when the rank is unused. */
    fun wordAt(rank: Int): String? = null

    fun contains(word: String): Boolean = rank(word) != null
}

/**
 * Reduces a surface form to the word a learner would say they know.
 *
 * Without this, "study", "studies" and "studied" are three vocabulary items,
 * which triples the apparent size of every text and scatters one piece of
 * knowledge across three records.
 *
 * The obvious rule - leave a word alone if the frequency list contains it -
 * does not work, and the list itself is why: it is built from running text, so
 * it holds "studies" at rank 3595 as well as "study" at 1266. Trusting mere
 * membership left every inflection uncollapsed and filed the same word in two
 * different difficulty bands.
 *
 * So a strip is attempted even for listed words, under three guards that were
 * settled by running the rules across all 10,000 entries and reading the
 * output rather than by reasoning about English:
 *
 *  1. The result must be in the list. This is the veto that makes crude rules
 *     safe: "only" does not become "on", because "onl" is not a word.
 *  2. The result must be *more* frequent than the surface form. An inflection
 *     is rarer than its base, so this rejects "his" -> "hi" and "yes" -> "ye".
 *  3. Of the surviving candidates, the longest wins - the most conservative
 *     strip. Picking the most frequent instead turned "ones" into "on".
 *
 * That left two wrong answers in the whole list, both of which are words that
 * merely end in an inflectional suffix; they are named in [EXCEPTIONS].
 */
class Lemmatizer(private val ranks: WordRanks) {

    fun lemma(raw: String): String {
        val w = raw.lowercase().trim('\'')
        if (w.isEmpty() || w in EXCEPTIONS) return w

        val listedRank = ranks.rank(w) ?: Int.MAX_VALUE

        inflections(w)
            .filter { (ranks.rank(it) ?: Int.MAX_VALUE) < listedRank }
            .minWithOrNull(compareBy({ -it.length }, { ranks.rank(it) ?: Int.MAX_VALUE }))
            ?.let { return it }

        if (listedRank != Int.MAX_VALUE) return w

        // Off the list entirely: nothing to corrupt, so the looser derivational
        // rules get a turn before giving up.
        derivations(w).forEach { if (ranks.contains(it)) return it }
        return fallback(w)
    }

    /**
     * Forms that are the same word as [w], not merely related to it.
     *
     * A minimum stem length rather than a general one: the failures were all
     * two-letter stems that happen to spell a word - "thing" via "th" + e, and
     * "used" via "us". Applying the same floor everywhere would have blocked
     * "going" -> "go" and "being" -> "be", which are correct and common.
     */
    private fun inflections(w: String): List<String> = buildList {
        if (w.endsWith("'s") && w.length > 3) add(w.dropLast(2))
        if (w.endsWith("s'") && w.length > 3) add(w.dropLast(1))

        if (w.endsWith("ies") && w.length > 4) add(w.dropLast(3) + "y")
        if (w.endsWith("es") && w.length > 3) { add(w.dropLast(2)); add(w.dropLast(1)) }
        // The "ss" guard keeps "class" from becoming "clas".
        if (w.endsWith("s") && !w.endsWith("ss") && w.length > 2) add(w.dropLast(1))

        if (w.endsWith("ied") && w.length > 4) add(w.dropLast(3) + "y")
        if (w.endsWith("ed") && w.length > 3) {
            if (w.length - 2 >= MIN_STEM) add(w.dropLast(2))          // walked -> walk
            if (w.length - 1 >= MIN_STEM) add(w.dropLast(1))          // hoped  -> hope
            if (doubled(w, 3) && w.length - 3 >= MIN_STEM) add(w.dropLast(3))  // stopped -> stop
        }
        if (w.endsWith("ing") && w.length > 4) {
            add(w.dropLast(3))                                        // going -> go
            if (w.length - 3 >= MIN_STEM) add(w.dropLast(3) + "e")    // hoping -> hope
            if (doubled(w, 4)) add(w.dropLast(4))                     // running -> run
        }
    }.filter { it.length >= 2 }

    /** Related words rather than the same word; only used when nothing else worked. */
    private fun derivations(w: String): List<String> = buildList {
        if (w.endsWith("ly") && w.length > 4) { add(w.dropLast(2)); add(w.dropLast(2) + "e") }
        if (w.endsWith("est") && w.length > 5) { add(w.dropLast(3)); add(w.dropLast(2)) }
        if (w.endsWith("er") && w.length > 4) { add(w.dropLast(2)); add(w.dropLast(1)) }
    }

    /** True when the letter before the suffix is a doubled consonant. */
    private fun doubled(w: String, suffixPlusOne: Int): Boolean {
        val i = w.length - suffixPlusOne
        return i > 0 && w[i] == w[i - 1] && w[i] !in "aeiou"
    }

    /**
     * Off-list and unrecognised. Collapsing the plural anyway keeps a proper
     * noun from being counted as two unknown words.
     */
    private fun fallback(w: String): String = when {
        w.endsWith("'s") && w.length > 3 -> w.dropLast(2)
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length > 3 -> w.dropLast(1)
        else -> w
    }

    companion object {
        private const val MIN_STEM = 3

        /**
         * The complete set of words the rules get wrong, found by running them
         * over the whole 10,000-word list. Both are ordinary words whose ending
         * only looks inflectional.
         */
        private val EXCEPTIONS = setOf("news", "evening")

        private val TOKEN = Regex("[a-z]+(?:'[a-z]+)?")

        /**
         * Words only. Digits are dropped rather than lemmatised: "2024" is not
         * vocabulary, and counting it as an unknown word would make every news
         * item look harder than it is.
         */
        fun tokenize(text: String): List<String> =
            TOKEN.findAll(text.lowercase())
                .map { it.value }
                .filter { it.length > 1 || it == "a" || it == "i" }
                .toList()
    }
}
