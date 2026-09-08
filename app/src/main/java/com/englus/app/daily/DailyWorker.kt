package com.englus.app.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.englus.app.exposure.ExposureSettings
import com.englus.app.exposure.SnoozeReceiver
import com.englus.app.widget.SentenceWidget
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Builds today's sentences before the learner asks for them.
 *
 * Generating on demand would put a network wait in front of the task every
 * time, and a task that makes you wait is one you skip. Doing it in the morning
 * means the card is already filled in whenever the app is opened.
 *
 * One request a day, on the text model, which the free tier covers outright.
 */
class DailyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = DailyRepository(applicationContext)
        if (!repo.configured) return Result.success()

        val sentences = runCatching { repo.generateToday() }.getOrElse {
            // A transient failure is worth one retry; the day is not urgent.
            return Result.retry()
        }
        if (sentences.isEmpty()) return Result.success()

        SentenceWidget.refresh(applicationContext)

        val exposure = ExposureSettings(applicationContext)
        if (exposure.active && exposure.generatedSentences) {
            notify(sentences.first().korean, sentences.size)
        }
        return Result.success()
    }

    private fun notify(korean: String, count: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "오늘의 문장", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val open = PendingIntent.getActivity(
            applicationContext,
            2,
            Intent().setClassName(applicationContext, "com.englus.app.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getBroadcast(
            applicationContext,
            3,
            Intent(applicationContext, SnoozeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The Korean, not the English: this is a task to produce, and showing
        // the answer in the notification would spend it before it is opened.
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("오늘의 문장 ${count}개")
            .setContentText(korean)
            .setStyle(NotificationCompat.BigTextStyle().bigText(korean))
            .setSmallIcon(com.englus.app.R.drawable.ic_stat_transcribe)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "오늘은 그만", snooze)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "daily"
        private const val NOTIFICATION_ID = 45
        private const val WORK_NAME = "daily-sentences"

        /** Morning, so the day's task is waiting rather than being generated at night. */
        private const val TARGET_HOUR = 7

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextTargetHour(), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun delayToNextTargetHour(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
