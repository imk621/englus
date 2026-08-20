package com.englus.app.feed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.englus.app.EnglusApp
import com.englus.app.R
import com.englus.app.data.DownloadState
import com.englus.app.data.EnglusDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads one episode's audio, then hands it to the transcription queue.
 *
 * Separate from TranscribeWorker on purpose: this one needs a network and no
 * CPU, that one needs CPU and no network. Fusing them would make a 40-minute
 * transcription hostage to connectivity it does not use.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        if (itemId < 0) return Result.failure()

        val dao = EnglusDatabase.get(applicationContext).feeds()
        val item = dao.getItem(itemId) ?: return Result.failure()
        val episodes = (applicationContext as EnglusApp).repository

        val dest = episodes.audioFileFor(safeName(item.title))

        return try {
            setForeground(foregroundInfo(item.title, 0))
            dao.setDownloadState(itemId, DownloadState.DOWNLOADING, 0f, null)

            withContext(Dispatchers.IO) {
                val conn = Http.open(item.audioUrl)
                try {
                    // The feed's advertised length is often absent or stale, so
                    // prefer what the server actually says it will send.
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: item.sizeBytes
                    conn.inputStream.use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val percent = (copied * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        dao.setDownloadState(
                                            itemId, DownloadState.DOWNLOADING, percent / 100f, null
                                        )
                                        runCatching { setForeground(foregroundInfo(item.title, percent)) }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }

            if (dest.length() == 0L) error("빈 파일을 받았습니다")

            val episodeId = episodes.registerDownloaded(item.title, dest)
            dao.updateItem(
                item.copy(
                    episodeId = episodeId,
                    downloadState = DownloadState.DONE,
                    downloadProgress = 1f,
                    error = null,
                )
            )
            Result.success()
        } catch (e: Throwable) {
            dest.delete()
            dao.setDownloadState(itemId, DownloadState.FAILED, 0f, e.message ?: e.toString())
            Result.failure()
        }
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { "episode" } + ".mp3"

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("다운로드 중")
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
        const val KEY_ITEM_ID = "feedItemId"
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 43
    }
}
