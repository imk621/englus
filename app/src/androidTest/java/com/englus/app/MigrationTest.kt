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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
