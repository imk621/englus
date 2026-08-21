package com.englus.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.data.EnglusDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone already holds transcribed episodes, and re-transcribing a 40-minute
 * podcast costs five minutes each. So the migration has to be additive and it has
 * to be right — a wrong CREATE TABLE only shows up as the app refusing to open.
 *
 * runMigrationsAndValidate compares the migrated database against the schema Room
 * generated for version 2, which is the check that hand-written SQL cannot fake.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EnglusDatabase::class.java,
    )

    @Test
    fun migrate1To2AddsFeedsAndKeepsTranscripts() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs) " +
                    "VALUES ('Day001', '/audio/day001.mp3', 600000, 1000, 'DONE', 1.0, NULL, 'ggml-base.en.bin', 252293)"
            )
            db.execSQL(
                "INSERT INTO sentences (episodeId, position, text, startMs, endMs, words, saved) " +
                    "VALUES (1, 0, 'In fact, no one gets to live an easy life,', 17000, 21000, '', 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT title, modelName FROM episodes").use { c ->
            assertTrue("episode lost in migration", c.moveToFirst())
            assertEquals("Day001", c.getString(0))
            assertEquals("ggml-base.en.bin", c.getString(1))
        }
        db.query("SELECT text, saved FROM sentences").use { c ->
            assertTrue("sentence lost in migration", c.moveToFirst())
            assertTrue("saved flag lost", c.getInt(1) == 1)
        }
        // New tables must exist and be writable, not merely declared.
        db.execSQL(
            "INSERT INTO podcasts (feedUrl, title, imageUrl, addedAt, lastCheckedAt) " +
                "VALUES ('https://example.com/f.xml', 'F', NULL, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO feed_items " +
                "(podcastId, guid, title, audioUrl, publishedAt, durationSec, sizeBytes, episodeId, downloadState, downloadProgress, error) " +
                "VALUES (1, 'g1', 'E1', 'https://example.com/a.mp3', 1, 60, 100, NULL, 'NONE', 0.0, NULL)"
        )
        db.query("SELECT COUNT(*) FROM feed_items").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    /** The path an installed phone actually takes: it already has podcasts. */
    @Test
    fun migrate2To3AddsDictationAndKeepsEverything() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs) " +
                    "VALUES ('6 Minute English', '/audio/e.mp3', 360000, 1, 'DONE', 1.0, NULL, 'ggml-base.en.bin', 40000)"
            )
            db.execSQL(
                "INSERT INTO sentences (episodeId, position, text, startMs, endMs, words, saved) " +
                    "VALUES (1, 0, 'Who does the housework?', 0, 3000, '', 1)"
            )
            db.execSQL(
                "INSERT INTO podcasts (feedUrl, title, imageUrl, addedAt, lastCheckedAt) " +
                    "VALUES ('https://podcasts.files.bbci.co.uk/p02pc9tn.rss', '6 Minute English', NULL, 1, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM sentences WHERE saved = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("saved sentence lost", 1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM podcasts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("subscription lost", 1, c.getInt(0))
        }
        db.execSQL(
            "INSERT INTO dictation_attempts " +
                "(sentenceId, episodeId, userText, correctText, accuracy, diagnosis, usedSlowReplay, attemptedAt) " +
                "VALUES (1, 1, 'who does the housework', 'Who does the housework?', 1.0, 'CORRECT', 0, 1)"
        )
        db.query("SELECT diagnosis FROM dictation_attempts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("CORRECT", c.getString(0))
        }
    }

    /** A phone that skipped the podcast build has to chain both migrations. */
    @Test
    fun migrate1To3ChainsBothMigrations() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs) " +
                    "VALUES ('Day001', '/a.mp3', 1000, 1, 'DONE', 1.0, NULL, 'm', 1)"
            )
        }
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true,
            MIGRATION_1_2, MIGRATION_2_3,
        )
        db.query("SELECT title FROM episodes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Day001", c.getString(0))
        }
    }

    /**
     * ADD COLUMN is the migration most likely to be silently wrong: SQLite needs a
     * DEFAULT for a NOT NULL column, and Room compares defaults when validating.
     * Existing rows must come out with the default rather than being dropped.
     */
    @Test
    fun migrate3To4AddsTranslationColumnsAndKeepsRows() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs) " +
                    "VALUES ('Day001', '/a.mp3', 1000, 1, 'DONE', 1.0, NULL, 'm', 1)"
            )
            db.execSQL(
                "INSERT INTO sentences (episodeId, position, text, startMs, endMs, words, saved) " +
                    "VALUES (1, 0, 'You have been a victim', 0, 2000, '', 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT translationState FROM episodes").use { c ->
            assertTrue("episode lost", c.moveToFirst())
            assertEquals("default not applied to existing row", "NONE", c.getString(0))
        }
        db.query("SELECT text, translation FROM sentences").use { c ->
            assertTrue("sentence lost", c.moveToFirst())
            assertEquals("You have been a victim", c.getString(0))
            assertTrue("translation should start null", c.isNull(1))
        }
        db.execSQL("UPDATE sentences SET translation = '당신은 피해자였습니다' WHERE id = 1")
        db.query("SELECT translation FROM sentences WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("당신은 피해자였습니다", c.getString(0))
        }
    }

    @Test
    fun migrate4To5AddsNotesAndCascadesWithSentences() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
        ).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs, translationState) " +
                    "VALUES ('Day001', '/a.mp3', 1000, 1, 'DONE', 1.0, NULL, 'm', 1, 'DONE')"
            )
            db.execSQL(
                "INSERT INTO sentences (episodeId, position, text, startMs, endMs, words, saved, translation) " +
                    "VALUES (1, 0, 'ask not what your country can do for you', 0, 2000, '', 1, '기계번역')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT translation FROM sentences").use { c ->
            assertTrue("sentence lost", c.moveToFirst())
            assertEquals("기계번역", c.getString(0))
        }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO sentence_notes " +
                "(sentenceId, translation, structure, expressions, grammar, sound, model, createdAt) " +
                "VALUES (1, '나라가 무엇을 해줄지 묻지 말라', '명령문', '[]', '부정 명령', " +
                "'can do for you 가 뭉쳐 들린다', 'gemini-3.7-flash', 1)"
        )
        db.query("SELECT translation FROM sentence_notes WHERE sentenceId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("나라가 무엇을 해줄지 묻지 말라", c.getString(0))
        }

        // Deleting the sentence must take its note with it, or notes outlive the
        // episode they explain and accumulate forever.
        db.execSQL("DELETE FROM sentences WHERE id = 1")
        db.query("SELECT COUNT(*) FROM sentence_notes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("note not cascaded", 0, c.getInt(0))
        }
    }

    @Test
    fun migrate5To6AddsSchedulingAndCascades() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 5, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        ).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs, translationState) " +
                    "VALUES ('Day001', '/a.mp3', 1000, 1, 'DONE', 1.0, NULL, 'm', 1, 'DONE')"
            )
            db.execSQL(
                "INSERT INTO sentences (episodeId, position, text, startMs, endMs, words, saved, translation) " +
                    "VALUES (1, 0, 'ask not what your country can do for you', 0, 2000, '', 1, '해석')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM sentences WHERE saved = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("saved sentence lost", 1, c.getInt(0))
        }

        // The backfill query is what actually creates cards; a typo there means
        // nothing is ever due and the whole feature silently does nothing.
        db.execSQL(
            "INSERT INTO review_cards (sentenceId, stability, difficulty, state, due, lastReview, reps, lapses, createdAt) " +
                "SELECT s.id, 0.0, 0.0, 'NEW', 100, 0, 0, 0, 100 FROM sentences s " +
                "WHERE s.saved = 1 AND NOT EXISTS (SELECT 1 FROM review_cards c WHERE c.sentenceId = s.id)"
        )
        db.query("SELECT sentenceId, state FROM review_cards").use { c ->
            assertTrue("backfill created nothing", c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("NEW", c.getString(1))
        }

        // Running it twice must not duplicate: the unique index plus NOT EXISTS
        // is what makes the backfill safe to call on every session.
        db.execSQL(
            "INSERT OR IGNORE INTO review_cards (sentenceId, stability, difficulty, state, due, lastReview, reps, lapses, createdAt) " +
                "SELECT s.id, 0.0, 0.0, 'NEW', 100, 0, 0, 0, 100 FROM sentences s " +
                "WHERE s.saved = 1 AND NOT EXISTS (SELECT 1 FROM review_cards c WHERE c.sentenceId = s.id)"
        )
        db.query("SELECT COUNT(*) FROM review_cards").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("backfill duplicated a card", 1, c.getInt(0))
        }

        db.execSQL(
            "INSERT INTO review_logs (cardId, rating, stateBefore, elapsedDays, scheduledDays, reviewedAt) " +
                "VALUES (1, 3, 'NEW', 0.0, 3, 200)"
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM sentences WHERE id = 1")
        db.query("SELECT COUNT(*) FROM review_cards").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("card not cascaded", 0, c.getInt(0))
        }
    }

    @Test
    fun migrate6To7AddsWritingAndCascades() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 6, true,
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        ).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.execSQL(
            "INSERT INTO writings (prompt, userText, correctedText, naturalText, overall, model, createdAt) " +
                "VALUES ('오늘 한 일', 'I go to school yesterday.', 'I went to school yesterday.', " +
                "'I was at school yesterday.', '시제만 고치면 됩니다.', 'gemini-3.7-flash', 1)"
        )
        db.execSQL(
            "INSERT INTO writing_mistakes (writingId, category, original, corrected, explanation) " +
                "VALUES (1, 'TENSE', 'go', 'went', 'yesterday 가 있으므로 과거형이어야 합니다.')"
        )
        db.query("SELECT category FROM writing_mistakes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("TENSE", c.getString(0))
        }

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM writings WHERE id = 1")
        db.query("SELECT COUNT(*) FROM writing_mistakes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("mistake not cascaded", 0, c.getInt(0))
        }
    }

    @Test
    fun migrate7To8AddsEpisodeKindWithListeningDefault() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 7, true,
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        ).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs, translationState) " +
                    "VALUES ('Day001', '/a.mp3', 1000, 1, 'DONE', 1.0, NULL, 'm', 1, 'DONE')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        // Everything that existed before reading was a thing is listening material.
        db.query("SELECT kind FROM episodes").use { c ->
            assertTrue("episode lost", c.moveToFirst())
            assertEquals("LISTENING", c.getString(0))
        }
        db.execSQL(
            "INSERT INTO episodes " +
                "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs, translationState, kind) " +
                "VALUES ('사진', '', 0, 2, 'DONE', 1.0, NULL, NULL, 0, 'NONE', 'READING')"
        )
        db.query("SELECT COUNT(*) FROM episodes WHERE kind = 'READING'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun migrate8To9AddsSpeakingAttempts() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 8, true,
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        ).use { db ->
            db.execSQL(
                "INSERT INTO episodes " +
                    "(title, audioPath, durationMs, importedAt, state, progress, error, modelName, transcribeMs, translationState, kind) " +
                    "VALUES ('Day001', '/a.mp3', 1000, 1, 'DONE', 1.0, NULL, 'm', 1, 'DONE', 'LISTENING')"
            )
            db.execSQL(
                "INSERT INTO sentences (episodeId, position, text, startMs, endMs, words, saved, translation) " +
                    "VALUES (1, 0, 'ask not what your country can do for you', 0, 2000, '', 0, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        db.execSQL(
            "INSERT INTO speaking_attempts " +
                "(sentenceId, episodeId, accuracy, recognizedText, targetText, spokenMs, targetMs, attemptedAt) " +
                "VALUES (1, 1, 0.85, 'ask not what your country can do for you', " +
                "'ask not what your country can do for you', 2400, 2000, 1)"
        )
        db.query("SELECT accuracy FROM speaking_attempts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.85, c.getDouble(0), 0.001)
        }

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM sentences WHERE id = 1")
        db.query("SELECT COUNT(*) FROM speaking_attempts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("attempt not cascaded", 0, c.getInt(0))
        }
    }

    @Test
    fun migrate9To10AddsExamsAndCascades() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 9, true,
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        ).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        db.execSQL(
            "INSERT INTO exam_sessions (episodeId, episodeTitle, total, correct, finished, elapsedMs, createdAt) " +
                "VALUES (1, 'Day001', 8, 6, 1, 120000, 1)"
        )
        db.execSQL(
            "INSERT INTO exam_questions " +
                "(sessionId, kind, position, prompt, options, correctIndex, explanation, startMs, endMs, chosenIndex) " +
                "VALUES (1, 'HEARD', 0, '', '[\"a\",\"b\",\"c\",\"d\"]', 0, '설명', 0, 2000, 0)"
        )
        db.query("SELECT kind, options FROM exam_questions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("HEARD", c.getString(0))
            assertTrue("options not stored as JSON", c.getString(1).startsWith("["))
        }

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM exam_sessions WHERE id = 1")
        db.query("SELECT COUNT(*) FROM exam_questions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("question not cascaded", 0, c.getInt(0))
        }
    }

    /**
     * The vocabulary profile, plus difficulty on episodes that already exist.
     *
     * The three episode columns must arrive nullable. A row transcribed before
     * this version was never measured, and a defaulted zero would show it in the
     * library as the hardest text there is.
     */
    @Test
    fun migrate10To11AddsVocabularyAndLeavesOldEpisodesUnmeasured() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO episodes (title, audioPath, durationMs, importedAt, state, progress, transcribeMs) " +
                    "VALUES ('Day001', '/audio/a.mp3', 60000, 1, 'DONE', 1.0, 500)"
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 10, true,
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
        ).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        db.query("SELECT title, cefr, k2Coverage, avgSentenceLen FROM episodes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Day001", c.getString(0))
            assertTrue("existing episode was given a difficulty it never had", c.isNull(1))
            assertTrue("k2Coverage defaulted instead of staying unknown", c.isNull(2))
            assertTrue("avgSentenceLen defaulted instead of staying unknown", c.isNull(3))
        }

        db.execSQL(
            "INSERT INTO vocab_words (lemma, rank, seen, correct, missed, state, manual, firstSeenAt, lastSeenAt) " +
                "VALUES ('study', 1266, 3, 2, 1, 'KNOWN', 0, 1, 2)"
        )
        // The primary key is the lemma, which is what stops the same word being
        // recorded twice under two inflections.
        db.execSQL(
            "INSERT OR REPLACE INTO vocab_words " +
                "(lemma, rank, seen, correct, missed, state, manual, firstSeenAt, lastSeenAt) " +
                "VALUES ('study', 1266, 4, 3, 1, 'KNOWN', 0, 1, 3)"
        )
        db.query("SELECT COUNT(*), MAX(seen) FROM vocab_words WHERE lemma = 'study'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("lemma duplicated", 1, c.getInt(0))
            assertEquals("upsert did not update", 4, c.getInt(1))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        val MIGRATION_1_2 = EnglusDatabase.MIGRATION_1_2
        val MIGRATION_2_3 = EnglusDatabase.MIGRATION_2_3
        val MIGRATION_3_4 = EnglusDatabase.MIGRATION_3_4
        val MIGRATION_4_5 = EnglusDatabase.MIGRATION_4_5
        val MIGRATION_5_6 = EnglusDatabase.MIGRATION_5_6
        val MIGRATION_6_7 = EnglusDatabase.MIGRATION_6_7
        val MIGRATION_7_8 = EnglusDatabase.MIGRATION_7_8
        val MIGRATION_8_9 = EnglusDatabase.MIGRATION_8_9
        val MIGRATION_9_10 = EnglusDatabase.MIGRATION_9_10
        val MIGRATION_10_11 = EnglusDatabase.MIGRATION_10_11
    }
}
