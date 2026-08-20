package com.englus.app.srs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.englus.app.data.EnglusDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * One daily nudge, and only when there is something to do.
 *
 * Spaced repetition fails silently: the cards come due and nobody notices. A
 * reminder is the only part of the loop the app cannot do for the learner. It
 * stays quiet on days with nothing due — a notification that always fires is one
 * that gets muted.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = EnglusDatabase.get(applicationContext).reviews()
        val now = System.currentTimeMillis()
        dao.backfill(now)

        val due = runCatching { dao.due(now).size }.getOrDefault(0)
        if (due == 0) return Result.success()

        notify(due)
        return Result.success()
    }

    private fun notify(due: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "복습 알림", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent().setClassName(applicationContext, "com.englus.app.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("복습할 문장 ${due}개")
            .setContentText("못 알아들었던 문장을 다시 들어볼 시간입니다.")
            .setSmallIcon(com.englus.app.R.drawable.ic_stat_transcribe)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        // Silently ignored when the user declined notifications, which is fine —
        // the app must not depend on the reminder to be usable.
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "review"
        private const val NOTIFICATION_ID = 44
        private const val WORK_NAME = "daily-review-reminder"

        /** Evening: cards are reviewed at the end of a day, not the start of one. */
        private const val TARGET_HOUR = 20

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextTargetHour(), TimeUnit.MILLISECONDS)
                .build()

            // KEEP so that every app launch does not reset the schedule and push
            // the reminder a day into the future.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        private fun delayToNextTargetHour(): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
