package com.englus.app

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.englus.app.asr.ModelStore
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.EpisodeRepository
import com.englus.app.data.TranscriptState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole app flow minus the file picker: import an audio file, let
 * WorkManager run the real worker, and check that playable sentences land in the
 * database.
 *
 * Worth its runtime because it is the only thing covering the wiring — Room
 * schema, type converters, the work request, the foreground service, and the
 * state machine the library screen renders. Each piece fails silently on its own.
 */
@RunWith(AndroidJUnit4::class)
class ImportAndTranscribeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    // runBlocking<Unit>, not a bare runBlocking: an expression body would return
    // the block's last value and JUnit4 rejects any test method that is not void.
    fun importedAudioBecomesPlayableSentences() = runBlocking<Unit> {
        assumeTrue("no model on device", ModelStore(context).preferred() != null)

        val staged = File(context.cacheDir, "e2e-jfk.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input ->
            staged.outputStream().use { input.copyTo(it) }
        }

        val repo = EpisodeRepository(context.applicationContext as Application)
        val dao = EnglusDatabase.get(context).episodes()

        val id = repo.import(Uri.fromFile(staged))
        assertTrue("import returned no id", id > 0)

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var state = TranscriptState.PENDING
        while (System.currentTimeMillis() < deadline) {
            state = dao.get(id)?.state ?: TranscriptState.FAILED
            if (state == TranscriptState.DONE || state == TranscriptState.FAILED) break
            Thread.sleep(500)
        }

        val episode = dao.get(id)
        assertEquals("transcription did not finish: ${episode?.error}", TranscriptState.DONE, state)
        assertTrue("duration not recorded", (episode?.durationMs ?: 0) > 5_000)
        assertTrue("transcribeMs not recorded", (episode?.transcribeMs ?: 0) > 0)

        val sentences = withTimeout(5_000) {
            dao.observeSentences(id).first { it.isNotEmpty() }
        }

        assertTrue("no sentences stored", sentences.isNotEmpty())
        assertTrue(
            "transcript wrong: ${sentences.joinToString(" ") { it.text }}",
            sentences.joinToString(" ") { it.text }.lowercase().contains("ask not what your country"),
        )
        // Words survive the type converter round trip — the player reads them back
        // out of this column and a broken encoding would silently empty karaoke.
        assertTrue("words lost in storage", sentences.any { it.words.size > 2 })

        repo.delete(episode!!)
        staged.delete()
    }

    private companion object {
        /** tiny.en on an emulator is glacial; on a phone this finishes in seconds. */
        const val TIMEOUT_MS = 400_000L
    }
}
