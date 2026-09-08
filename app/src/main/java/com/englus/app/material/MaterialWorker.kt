package com.englus.app.material

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.englus.app.data.DownloadState
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.feedsForLevel
import com.englus.app.feed.FeedRepository
import com.englus.app.level.LevelStore
import com.englus.app.vocab.VocabRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Brings in new material without being asked.
 *
 * Everything the app can do rested on the learner first finding something to
 * listen to: subscribe, refresh, choose, download, wait for the transcript.
 * That is five steps standing in front of every session, and a step in front of
 * a habit is the reason the habit does not form.
 *
 * The pick is random within the feeds that suit the measured level. Random is
 * the point - the same three episodes on rotation stop being input and start
 * being furniture - but random across the whole internet is not a thing that
 * exists: arbitrary audio has no transcript and no known difficulty. Random
 * within a level-tagged pool is the version that works.
 */
class MaterialWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = MaterialSettings(applicationContext)
        if (!settings.enabled) return Result.success()

        // Built directly rather than reached through the Application: the worker
        // is not always handed the Application instance, and casting to it threw
        // ClassCastException the first time this was run outside the app.
        val feeds = FeedRepository(applicationContext)
        val dao = EnglusDatabase.get(applicationContext).feeds()

        val level = levelLabel()
        val wanted = feedsForLevel(level)

        // Subscribing here rather than making the learner do it first: the whole
        // point is that nothing has to be set up before material starts arriving.
        wanted.forEach { preset ->
            runCatching {
                if (dao.findByUrl(preset.feedUrl) == null) feeds.subscribe(preset.feedUrl)
            }
        }

        val subscribed = dao.observePodcasts().first()
            .filter { podcast -> wanted.any { it.feedUrl == podcast.feedUrl } }
        if (subscribed.isEmpty()) return Result.retry()

        subscribed.forEach { runCatching { feeds.refresh(it.id) } }

        // Nothing already downloaded, and nothing enormous: a two-hour episode
        // is half an hour of transcription and will not be listened to anyway.
        val candidates = subscribed.flatMap { podcast ->
            dao.observeItems(podcast.id).first()
        }.filter {
            it.downloadState == DownloadState.NONE &&
                it.episodeId == null &&
                it.durationSec in 0..MAX_DURATION_SEC
        }
        if (candidates.isEmpty()) return Result.success()

        val random = Random(System.currentTimeMillis())
        candidates.shuffled(random).take(settings.perDay).forEach { item ->
            feeds.markQueued(item.id)
            feeds.download(item.id)
        }

        settings.lastFetchAt = System.currentTimeMillis()
        return Result.success()
    }

    private suspend fun levelLabel(): String? {
        LevelStore(applicationContext).cefr?.let { return it.label }
        val stats = EnglusDatabase.get(applicationContext).vocab().observeBandStats().first()
        val estimate = VocabRepository.estimateSize(stats) ?: return null
        return when {
            estimate < 1500 -> "A2"
            estimate < 2500 -> "B1"
            estimate < 4000 -> "B2"
            else -> "C1"
        }
    }

    companion object {
        private const val WORK_NAME = "daily-material"

        /** Two hours of audio is half an hour of transcription for one episode. */
        private const val MAX_DURATION_SEC = 3_600

        /**
         * Unmetered and charging.
         *
         * Downloading tens of megabytes and then running the transcriber for
         * minutes is not something to do on someone's mobile data or on the
         * last of their battery. Waiting until the phone is on a charger at home
         * costs nothing - the material is for tomorrow either way.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaterialWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
