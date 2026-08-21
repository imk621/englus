package com.englus.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.VocabState
import com.englus.app.dictation.WordDiff
import com.englus.app.vocab.FrequencyList
import com.englus.app.vocab.Lemmatizer
import com.englus.app.vocab.VocabRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ingest path end to end, on a device.
 *
 * The JVM tests cover the rules; this covers the parts only Android has - the
 * frequency list actually being packaged in the APK and readable, and the writes
 * landing in Room without colliding on the lemma key.
 */
@RunWith(AndroidJUnit4::class)
class VocabIngestTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = EnglusDatabase.get(context).vocab()
    private val repo = VocabRepository(context)

    @Before
    fun clearProfile() = runBlocking {
        EnglusDatabase.get(context).openHelper.writableDatabase
            .execSQL("DELETE FROM vocab_words")
    }

    @Test
    fun frequencyListIsPackagedInTheApk() {
        val ranks = FrequencyList.get(context)
        assertEquals("asset missing or truncated", 10_000, ranks.size)
        assertNotNull(ranks.rank("the"))
        assertTrue("ranks out of order", ranks.rank("the")!! < ranks.rank("difficult")!!)
    }

    @Test
    fun scoredWordsBecomeAProfile() = runBlocking {
        val reference = "She studies the market every morning"

        // Typed correctly apart from one word.
        repo.ingest(WordDiff.compare(reference, "She studies the market every evening"))

        val words = dao.get(listOf("study", "market", "morning", "every"))
            .associateBy { it.lemma }

        // "studies" must be filed under "study" or the profile splits one word
        // across two entries in two different difficulty bands.
        assertNotNull("inflection not collapsed: ${words.keys}", words["study"])
        assertEquals(1, words["study"]!!.correct)
        assertEquals(VocabState.LEARNING, words["study"]!!.state)

        assertNotNull(words["morning"])
        assertEquals("missed word not recorded", 1, words["morning"]!!.missed)
        assertEquals(VocabState.UNKNOWN, words["morning"]!!.state)
    }

    @Test
    fun twoCleanHitsAreNeededBeforeAWordCountsAsKnown() = runBlocking {
        val reference = "The market opened early"
        repo.ingest(WordDiff.compare(reference, reference))
        assertEquals(VocabState.LEARNING, dao.get(listOf("market")).single().state)

        repo.ingest(WordDiff.compare(reference, reference))
        val market = dao.get(listOf("market")).single()
        assertEquals(VocabState.KNOWN, market.state)
        assertEquals("row duplicated instead of updated", 2, market.seen)
    }

    /**
     * Shadowing compares against speech recognition, so a mismatch there is not
     * evidence about vocabulary - it may be the recogniser's fault.
     */
    @Test
    fun shadowingRecordsOnlyWhatWentRight() = runBlocking {
        repo.ingest(
            WordDiff.compare("The market opened early", "The market opened quickly"),
            correctOnly = true,
        )
        assertEquals("a shadowing miss was recorded as unknown", 0, dao.get(listOf("early")).size)
        assertEquals(1, dao.get(listOf("market")).single().correct)
    }

    @Test
    fun aHandJudgementOverridesTheEvidence() = runBlocking {
        repo.ingest(WordDiff.compare("The market opened early", "The x opened early"))
        assertEquals(VocabState.UNKNOWN, dao.get(listOf("market")).single().state)

        repo.setKnown("market", true)
        assertEquals(VocabState.KNOWN, dao.get(listOf("market")).single().state)

        // Later evidence must not quietly undo what the learner said.
        repo.ingest(WordDiff.compare("The market opened early", "The y opened early"))
        assertEquals(VocabState.KNOWN, dao.get(listOf("market")).single().state)
    }

    @Test
    fun unknownRateFallsAsTheProfileGrows() = runBlocking {
        val text = listOf("The market opened early and the traders arrived")
        val before = repo.unknownRate(text)
        assertEquals(before.totalTokens, before.unknownTokens)

        repeat(2) { repo.ingest(WordDiff.compare(text.single(), text.single())) }
        val after = repo.unknownRate(text)
        assertTrue(
            "rate did not fall: ${before.rate} -> ${after.rate}",
            after.rate < before.rate,
        )
        assertEquals("everything is known now", 0, after.unknownTokens)
    }

    @Test
    fun difficultyProfileRunsOnRealText() = runBlocking {
        val simple = List(20) { "The man walked to the shop and bought some bread today" }
        val profile = repo.profile(simple)
        assertTrue("k2 coverage implausible: ${profile.k2Coverage}", profile.k2Coverage > 0.9f)
        assertTrue(profile.tokenCount >= VocabRepository.MIN_TOKENS_FOR_ESTIMATE)
    }

    @Test
    fun bandStatsBucketByFrequency() = runBlocking {
        repo.ingest(WordDiff.compare("the market", "the market"))
        val stats = dao.observeBandStats().first()
        assertTrue("no bands produced", stats.isNotEmpty())
        assertEquals("counts do not add up", 2, stats.sumOf { it.met })
        // "the" is the most common word in English; it cannot land outside K1.
        assertTrue("the was not in the first band", stats.any { it.band == 0 })
    }

    /**
     * The half of the vocabulary profile that turns a measurement into practice:
     * an unknown word has to lead back to the sentences it was met in, including
     * the ones where it appears in a different form.
     */
    @Test
    fun findsTheSentencesAWordWasMetIn() = runBlocking {
        val episodes = EnglusDatabase.get(context).episodes()
        val id = episodes.insert(
            com.englus.app.data.Episode(
                title = "word-lookup-${System.nanoTime()}",
                audioPath = "",
                importedAt = 1,
                state = com.englus.app.data.TranscriptState.DONE,
            )
        )
        episodes.insertSentences(
            listOf(
                "She studies the market every morning",
                "He is hoping for a better price",
                "They walked to the shop",
            ).mapIndexed { i, text ->
                com.englus.app.data.Sentence(
                    episodeId = id, position = i, text = text,
                    startMs = 0, endMs = 1000, words = emptyList(),
                )
            }
        )

        // "studies" in the text, "study" in the profile: the whole point.
        val forStudy = repo.sentencesFor("study")
        assertTrue(
            "inflected form not found: ${forStudy.map { it.sentence.text }}",
            forStudy.any { it.sentence.text.contains("studies") },
        )

        // "hope" does not occur inside "hoping" as a substring.
        val forHope = repo.sentencesFor("hope")
        assertTrue(
            "e-dropping form not found: ${forHope.map { it.sentence.text }}",
            forHope.any { it.sentence.text.contains("hoping") },
        )

        // The widened search must not leak: "shop" shares letters with "hop".
        assertTrue(
            "unrelated sentence matched",
            repo.sentencesFor("hope").none { it.sentence.text.contains("walked to the shop") },
        )

        assertTrue("a word not present returned rows", repo.sentencesFor("zebra").isEmpty())
        episodes.delete(id)
    }

    @Test
    fun tokenizerAndLemmatizerAgreeOnRealSentences() {
        val lem = Lemmatizer(FrequencyList.get(context))
        val tokens = Lemmatizer.tokenize("In 2024, the studies showed prices were rising.")
        assertTrue("digits leaked in", tokens.none { it.any(Char::isDigit) })
        assertEquals("study", lem.lemma("studies"))
        assertEquals("rise", lem.lemma("rising"))
    }
}
