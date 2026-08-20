package com.englus.app.translate

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.TranslationState
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Fills in Korean for every sentence of an episode, on device.
 *
 * Runs as its own job rather than inside TranscribeWorker: transcription is the
 * expensive, CPU-bound part and must not be retried or held up because a
 * translation model download failed. An episode with no translations is still
 * completely usable for listening.
 */
class TranslateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (episodeId < 0) return Result.failure()

        val dao = EnglusDatabase.get(applicationContext).episodes()
        val pending = dao.untranslated(episodeId)
        if (pending.isEmpty()) {
            dao.setTranslationState(episodeId, TranslationState.DONE)
            return Result.success()
        }

        dao.setTranslationState(episodeId, TranslationState.RUNNING)

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )

        return try {
            withContext(Dispatchers.IO) {
                // ~30 MB, once per device. No network constraint on the work
                // request: after the first run this is offline, and constraining
                // it would strand a locally imported file until the phone
                // happened to be online.
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

                pending.forEach { sentence ->
                    val ko = translator.translate(sentence.text).await()
                    if (ko.isNotBlank()) dao.setTranslation(sentence.id, ko)
                }
            }
            dao.setTranslationState(episodeId, TranslationState.DONE)
            Result.success()
        } catch (e: Throwable) {
            dao.setTranslationState(episodeId, TranslationState.FAILED)
            // Retry rather than fail outright: the usual cause is the model
            // download having no network yet, which fixes itself.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            translator.close()
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        private const val MAX_ATTEMPTS = 5
    }
}
