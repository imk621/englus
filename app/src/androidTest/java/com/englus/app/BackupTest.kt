package com.englus.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.backup.BackupRepository
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Episode
import com.englus.app.data.Sentence
import com.englus.app.data.TranscriptState
import com.englus.app.data.Word
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A backup that silently produces an unusable file is worse than none: it is
 * discovered only on the day it is needed. So the round trip is exercised for
 * real - write the archive, destroy the data, read it back - rather than
 * checking that export returned without throwing.
 */
@RunWith(AndroidJUnit4::class)
class BackupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = BackupRepository(context)

    private fun db() = EnglusDatabase.get(context)

    private suspend fun seed(title: String): Long {
        val id = db().episodes().insert(
            Episode(
                title = title,
                audioPath = "/nonexistent/audio.mp3",
                importedAt = 1,
                state = TranscriptState.DONE,
            )
        )
        db().episodes().insertSentences(
            listOf(
                Sentence(
                    episodeId = id,
                    position = 0,
                    text = "The market opened early",
                    startMs = 0,
                    endMs = 1000,
                    words = listOf(Word("The", 0, 100)),
                    saved = true,
                )
            )
        )
        return id
    }

    private fun tempFile(): File =
        File(context.cacheDir, "backup-test-${System.nanoTime()}.zip")

    @Test
    fun roundTripsTheLearningHistory() = runBlocking {
        val marker = "backup-marker-${System.nanoTime()}"
        seed(marker)

        val file = tempFile()
        try {
            val exported = repo.export(Uri.fromFile(file))
            assertTrue("nothing written", file.length() > 0)
            assertTrue("marker episode not counted", exported.episodes >= 1)
            assertTrue("saved sentence not counted", exported.savedSentences >= 1)

            // Destroy exactly what the backup is supposed to bring back.
            val before = db().episodes().all().single { it.title == marker }
            db().episodes().delete(before.id)
            assertTrue(db().episodes().all().none { it.title == marker })

            val restored = repo.restore(Uri.fromFile(file))
            assertEquals(BackupRepository.CURRENT_SCHEMA, restored.schemaVersion)

            val after = db().episodes().all().singleOrNull { it.title == marker }
            assertTrue("episode did not come back", after != null)
            val sentences = db().episodes().observeSentences(after!!.id).first()
            assertTrue(
                "the saved flag is the whole point and it was lost",
                sentences.single().saved,
            )
        } finally {
            file.delete()
        }
    }

    /**
     * WAL is the failure that would not show up in a quick manual test: without
     * a checkpoint the newest writes stay in the side file and the archive
     * restores to some earlier moment.
     */
    @Test
    fun includesWritesMadeMomentsBeforeTheExport() = runBlocking {
        val marker = "wal-marker-${System.nanoTime()}"
        val file = tempFile()
        try {
            seed(marker)
            repo.export(Uri.fromFile(file))

            val id = db().episodes().all().single { it.title == marker }.id
            db().episodes().delete(id)

            repo.restore(Uri.fromFile(file))
            assertTrue(
                "a write made just before export was not in the archive",
                db().episodes().all().any { it.title == marker },
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun refusesFilesThatAreNotBackups() = runBlocking {
        val junk = tempFile().apply { writeText("this is not a zip") }
        try {
            val e = runCatching { repo.inspect(Uri.fromFile(junk)) }.exceptionOrNull()
            assertTrue("junk accepted as a backup", e is BackupRepository.BackupException)
        } finally {
            junk.delete()
        }
    }

    /**
     * Audio is deliberately not in the archive, so restored episodes point at
     * files that no longer exist. Leaving the path would hand the player
     * something guaranteed to fail.
     */
    @Test
    fun clearsAudioPathsThatNoLongerResolve() = runBlocking {
        val marker = "audio-marker-${System.nanoTime()}"
        seed(marker)

        val cleared = repo.reconcileMissingAudio()
        assertTrue("nothing reconciled", cleared >= 1)

        val episode = db().episodes().all().single { it.title == marker }
        assertEquals("dangling audio path kept", "", episode.audioPath)
    }

    @Test
    fun namesFilesByDateSoTheySortAndDoNotOverwrite() {
        val a = BackupRepository.suggestedFileName(0L)
        val b = BackupRepository.suggestedFileName(86_400_000L * 400)
        assertTrue(a.startsWith("englus-backup-") && a.endsWith(".zip"))
        assertTrue("same name for different days", a != b)
    }
}
