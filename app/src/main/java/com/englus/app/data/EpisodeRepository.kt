package com.englus.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.englus.app.asr.TranscribeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EpisodeRepository(private val context: Context) {

    private val dao = EnglusDatabase.get(context).episodes()
    private val audioDir = File(context.getExternalFilesDir(null), "audio").apply { mkdirs() }

    fun observeEpisodes() = dao.observeAll()
    fun observeEpisode(id: Long) = dao.observe(id)
    fun observeSentences(episodeId: Long) = dao.observeSentences(episodeId)
    fun observeSaved() = dao.observeSaved()

    suspend fun setSaved(sentenceId: Long, saved: Boolean) = dao.setSaved(sentenceId, saved)

    /**
     * Copies the picked audio into app storage before registering it.
     *
     * Keeping only the SAF Uri would be cheaper, but those grants can be revoked
     * and the file can move; an episode whose audio vanishes takes its whole
     * transcript with it. Audio is the one thing worth the disk.
     */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val name = displayName(uri).ifEmpty { "audio-${System.currentTimeMillis()}" }
        val dest = File(audioDir, "${System.currentTimeMillis()}-$name")

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "오디오 파일을 열 수 없습니다" }
            dest.outputStream().use { input.copyTo(it, DEFAULT_BUFFER_SIZE) }
        }

        val id = dao.insert(
            Episode(
                title = name.substringBeforeLast('.'),
                audioPath = dest.absolutePath,
                importedAt = System.currentTimeMillis(),
                state = TranscriptState.PENDING,
            )
        )
        enqueue(id)
        id
    }

    /** Where downloads should land so [registerDownloaded] can adopt them. */
    fun audioFileFor(name: String): File = File(audioDir, "${System.currentTimeMillis()}-${name.take(80)}")

    /**
     * Registers audio already on disk (a podcast download) as a transcribable
     * episode. Unlike [import] there is nothing to copy — the file was written
     * straight into the audio directory.
     */
    suspend fun registerDownloaded(title: String, file: File): Long = withContext(Dispatchers.IO) {
        val id = dao.insert(
            Episode(
                title = title,
                audioPath = file.absolutePath,
                importedAt = System.currentTimeMillis(),
                state = TranscriptState.PENDING,
            )
        )
        enqueue(id)
        id
    }

    fun enqueue(episodeId: Long) {
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(Data.Builder().putLong(TranscribeWorker.KEY_EPISODE_ID, episodeId).build())
            // Not requiring charging or unmetered: transcription is local, costs
            // no network, and making the user wait for a charger to hear an
            // episode they just imported would be the wrong trade.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("transcribe-$episodeId", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun delete(episode: Episode) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("transcribe-${episode.id}")
        File(episode.audioPath).delete()
        dao.delete(episode.id)   // sentences cascade
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}
