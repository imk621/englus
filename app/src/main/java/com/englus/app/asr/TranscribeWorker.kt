package com.englus.app.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.englus.app.EnglusApp
import com.englus.app.R
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.TranscriptState
import com.englus.asr.AudioDecoder
import com.englus.asr.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcribes one episode in the background.
 *
 * Phase 0 measured 4m53s for a 40-minute episode on an S25. That is fine for a
 * job you queue and forget, and unacceptable as something a user waits on — so
 * this is a worker, not a foreground call, and it runs as a foreground service
 * because WorkManager will otherwise kill it around the 10-minute mark.
 */
class TranscribeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (episodeId < 0) return Result.failure()

        val dao = EnglusDatabase.get(applicationContext).episodes()
        val episode = dao.get(episodeId) ?: return Result.failure()

        val model = ModelStore(applicationContext).preferred()
            ?: return fail(episodeId, "whisper 모델이 없습니다. 설정에서 모델을 가져오세요.")

        return try {
            setForeground(foregroundInfo(episode.title, 0))
            dao.setState(episodeId, TranscriptState.RUNNING, 0f, null)

            withContext(Dispatchers.Default) {
                coroutineScope {
                    var lastPercent = -1
                    val pcm = AudioDecoder.decode(
                        applicationContext,
                        Uri.fromFile(File(episode.audioPath)),
                    ) { p ->
                        // Decoding is a real share of the wall clock (40s of the
                        // 4m53s measured), so it gets its own slice of the
                        // progress bar instead of the user staring at 0%.
                        //
                        // The callback is not a coroutine context, and it fires
                        // per codec buffer -- hundreds of times. Only whole
                        // percent changes are worth a database write and a
                        // notification update.
                        val scaled = p * 0.15f
                        val percent = (scaled * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            launch { reportProgress(episodeId, dao, scaled, episode.title) }
                        }
                    }

                    val ptr = WhisperNative.initContext(
                        model.absolutePath,
                        /* useDtw = */ false,
                        WhisperNative.Aheads.forModelFile(model.name),
                    )
                    if (ptr == 0L) throw IllegalStateException("모델 로딩 실패: ${model.name}")

                    val started = System.currentTimeMillis()
                    val json = try {
                        reportProgress(episodeId, dao, 0.2f, episode.title)
                        WhisperNative.transcribe(
                            ptr = ptr,
                            audio = pcm.samples,
                            nSamples = pcm.sampleCount,
                            nThreads = threadCount(),
                            language = "en",
                            tokenTimestamps = true,
                            useDtw = false,
                            maxLen = 0,
                            splitOnWord = true,
                        )
                    } finally {
                        WhisperNative.freeContext(ptr)
                    }
                    val elapsed = System.currentTimeMillis() - started

                    val sentences = TranscriptParser.parse(episodeId, json)
                    dao.clearSentences(episodeId)
                    dao.insertSentences(sentences)
                    dao.update(
                        episode.copy(
                            durationMs = (pcm.durationSec * 1000).toLong(),
                            state = TranscriptState.DONE,
                            progress = 1f,
                            error = null,
                            modelName = model.name,
                            transcribeMs = elapsed,
                        )
                    )
                }
            }
            // Only now, when there are sentences to translate.
            (applicationContext as EnglusApp).repository.enqueueTranslation(episodeId)
            // Difficulty is measured once and stored: the library shows a badge
            // for every episode, and profiling a 40-minute transcript on each
            // list render would be thousands of lookups per frame.
            (applicationContext as EnglusApp).vocab.measureEpisode(episodeId)
            Result.success()
        } catch (e: Throwable) {
            fail(episodeId, e.message ?: e.toString())
        }
    }

    private suspend fun reportProgress(
        id: Long,
        dao: com.englus.app.data.EpisodeDao,
        p: Float,
        title: String,
    ) {
        dao.setState(id, TranscriptState.RUNNING, p.coerceIn(0f, 1f), null)
        runCatching { setForeground(foregroundInfo(title, (p * 100).toInt())) }
    }

    private suspend fun fail(id: Long, message: String): Result {
        EnglusDatabase.get(applicationContext).episodes()
            .setState(id, TranscriptState.FAILED, 0f, message)
        return Result.failure()
    }

    /**
     * Half the cores. Phase 0 ran 4 of 8 and hit RTF 0.105; piling threads onto
     * the little cores costs more in contention than it buys, and the thread
     * sweep that would pin the real optimum has not been run yet.
     */
    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "전사", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("전사 중")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        private const val CHANNEL = "transcribe"
        private const val NOTIFICATION_ID = 42
    }
}
