package com.englus.app

import com.englus.app.vocab.Lemmatizer
import com.englus.app.vocab.WordRanks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The real bundled list, so the tests exercise the vetoes that make stripping safe. */
object RealRanks : WordRanks {
    private val map: Map<String, Int> = File("src/main/assets/en_freq_10k.txt")
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .withIndex()
        .associate { (i, w) -> w to i + 1 }

    override fun rank(word: String): Int? = map[word]
    override val size: Int get() = map.size
}

class LemmatizerTest {

    private val lem = Lemmatizer(RealRanks)

    @Test
    fun listIsBundledAndComplete() {
        assertEquals(10_000, RealRanks.size)
        assertTrue("the should be near the top", (RealRanks.rank("the") ?: 99999) < 10)
    }

    @Test
    fun collapsesInflections() {
        assertEquals("study", lem.lemma("studies"))
        assertEquals("study", lem.lemma("studied"))
        assertEquals("walk", lem.lemma("walked"))
        assertEquals("walk", lem.lemma("walking"))
        assertEquals("hope", lem.lemma("hoping"))
        assertEquals("run", lem.lemma("running"))
        assertEquals("stop", lem.lemma("stopped"))
        assertEquals("car", lem.lemma("cars"))
    }

    /**
     * The whole reason candidates are checked against the list. Blind stripping
     * turns these into words that mean something else, which would quietly
     * corrupt every count that depends on the lemma.
     */
    @Test
    fun doesNotStripWordsThatOnlyLookInflected() {
        assertEquals("only", lem.lemma("only"))
        assertEquals("bus", lem.lemma("bus"))
        assertEquals("class", lem.lemma("class"))
        assertEquals("this", lem.lemma("this"))
        assertEquals("during", lem.lemma("during"))
        // The two words in the whole list whose ending only looks inflectional.
        assertEquals("news", lem.lemma("news"))
        assertEquals("evening", lem.lemma("evening"))
        assertEquals("water", lem.lemma("water"))
        assertEquals("interest", lem.lemma("interest"))
    }

    @Test
    fun leavesIrregularsAloneBecauseTheListHasThem() {
        listOf("was", "were", "children", "went", "better", "best", "men").forEach {
            assertEquals("irregular $it was altered", it, lem.lemma(it))
        }
    }

    @Test
    fun normalisesCaseAndPossessives() {
        assertEquals("company", lem.lemma("Company"))
        assertEquals("company", lem.lemma("company's"))
        // Off-list proper noun: still collapsed so it counts once, not twice.
        assertEquals(lem.lemma("Valencia"), lem.lemma("Valencia's"))
    }

    /**
     * "don't" has no strippable ending, so it stands as its own item. "it's"
     * does, and reducing it to "it" is right: knowing the contraction is not
     * separate vocabulary from knowing the word.
     */
    @Test
    fun handlesContractions() {
        assertEquals("don't", lem.lemma("don't"))
        assertEquals("it", lem.lemma("it's"))
    }

    /**
     * The bug this class exists to avoid. The frequency list is built from
     * running text, so it holds inflected forms as entries of their own -
     * "studies" sits 2,300 ranks below "study". Leaving a word alone merely
     * because the list contains it filed the same word in two difficulty bands.
     */
    @Test
    fun collapsesInflectionsThatAreThemselvesInTheList() {
        assertTrue("premise changed: studies is no longer listed", RealRanks.contains("studies"))
        assertEquals("study", lem.lemma("studies"))
        assertEquals("go", lem.lemma("going"))
        assertEquals("be", lem.lemma("being"))
        assertEquals("one", lem.lemma("ones"))
        assertEquals("do", lem.lemma("does"))
    }

    /**
     * Two-letter stems that happen to spell a word were the only way a strip
     * produced a different word: "thing" via "th"+e, "used" via "us".
     */
    @Test
    fun doesNotStripOntoATwoLetterCoincidence() {
        assertEquals("thing", lem.lemma("thing"))
        assertTrue("used collapsed onto us", lem.lemma("used") in setOf("use", "used"))
    }

    @Test
    fun tokenizerDropsDigitsAndKeepsContractions() {
        val tokens = Lemmatizer.tokenize("In 2024 they didn't go to 3 shops.")
        assertTrue("digits leaked in: $tokens", tokens.none { it.any(Char::isDigit) })
        assertTrue("contraction split: $tokens", tokens.contains("didn't"))
        // Single letters other than a/i are fragments, not words.
        assertEquals(emptyList<String>(), Lemmatizer.tokenize("x y z"))
    }
}
