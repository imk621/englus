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

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, EnglusDatabase.MIGRATION_1_2)

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
        helper.runMigrationsAndValidate(TEST_DB, 2, true, EnglusDatabase.MIGRATION_1_2).use { db ->
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

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, EnglusDatabase.MIGRATION_2_3)

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
            EnglusDatabase.MIGRATION_1_2, EnglusDatabase.MIGRATION_2_3,
        )
        db.query("SELECT title FROM episodes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Day001", c.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
