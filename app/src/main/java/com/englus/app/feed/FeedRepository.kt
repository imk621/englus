package com.englus.app.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.englus.app.data.DownloadState
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.FeedItem
import com.englus.app.data.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FeedRepository(private val context: Context) {

    private val dao = EnglusDatabase.get(context).feeds()

    fun observePodcasts() = dao.observePodcasts()
    fun observePodcast(id: Long) = dao.observePodcast(id)
    fun observeItems(podcastId: Long) = dao.observeItems(podcastId)

    /** Adds a feed, or refreshes it if already subscribed. Returns the podcast id. */
    suspend fun subscribe(feedUrl: String): Long = withContext(Dispatchers.IO) {
        val url = feedUrl.trim()
        val parsed = fetch(url)

        val existing = dao.findByUrl(url)
        val id = if (existing != null) {
            dao.updatePodcast(
                existing.copy(
                    title = parsed.title,
                    imageUrl = parsed.imageUrl ?: existing.imageUrl,
                    lastCheckedAt = System.currentTimeMillis(),
                )
            )
            existing.id
        } else {
            dao.insertPodcast(
                Podcast(
                    feedUrl = url,
                    title = parsed.title,
                    imageUrl = parsed.imageUrl,
                    addedAt = System.currentTimeMillis(),
                    lastCheckedAt = System.currentTimeMillis(),
                )
            ).let { inserted ->
                // insertPodcast IGNOREs a duplicate feedUrl and returns -1; the
                // unique index is the real guard against double subscription.
                if (inserted > 0) inserted else dao.findByUrl(url)!!.id
            }
        }

        storeItems(id, parsed)
        id
    }

    suspend fun refresh(podcastId: Long) = withContext(Dispatchers.IO) {
        val podcast = dao.getPodcast(podcastId) ?: return@withContext
        val parsed = fetch(podcast.feedUrl)
        dao.updatePodcast(
            podcast.copy(
                title = parsed.title,
                imageUrl = parsed.imageUrl ?: podcast.imageUrl,
                lastCheckedAt = System.currentTimeMillis(),
            )
        )
        storeItems(podcastId, parsed)
    }

    suspend fun unsubscribe(podcastId: Long) = withContext(Dispatchers.IO) {
        dao.deletePodcast(podcastId)
    }

    private fun fetch(url: String): ParsedFeed =
        Http.get(url) { stream, _ -> RssParser.parse(stream) }

    private suspend fun storeItems(podcastId: Long, parsed: ParsedFeed) {
        dao.insertItems(
            parsed.items.map { p ->
                FeedItem(
                    podcastId = podcastId,
                    guid = p.guid,
                    title = p.title,
                    audioUrl = p.audioUrl,
                    publishedAt = p.publishedAt,
                    durationSec = p.durationSec,
                    sizeBytes = p.sizeBytes,
                )
            }
        )
    }

    fun download(itemId: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putLong(DownloadWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("download-$itemId", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun markQueued(itemId: Long) =
        dao.setDownloadState(itemId, DownloadState.QUEUED, 0f, null)
}
