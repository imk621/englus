package com.englus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val imageUrl: String? = null,
    val addedAt: Long,
    val lastCheckedAt: Long = 0,
)

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

/**
 * One episode as advertised by the feed — not yet audio on disk.
 *
 * Kept in the database rather than re-fetched each time so the list works
 * offline and, more importantly, so "already downloaded" survives a refresh.
 */
@Entity(
    tableName = "feed_items",
    indices = [Index("podcastId"), Index(value = ["podcastId", "guid"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = Podcast::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class FeedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationSec: Int = 0,
    val sizeBytes: Long = 0,
    /** Set once the audio is on disk and registered as a transcribable Episode. */
    val episodeId: Long? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadProgress: Float = 0f,
    val error: String? = null,
)

class FeedConverters {
    @TypeConverter fun stateToString(s: DownloadState): String = s.name

    @TypeConverter
    fun stringToState(s: String): DownloadState =
        runCatching { DownloadState.valueOf(s) }.getOrDefault(DownloadState.NONE)
}

/** Starter feeds, ordered as a difficulty ladder. */
data class PresetFeed(val title: String, val level: String, val feedUrl: String)

val PRESET_FEEDS = listOf(
    PresetFeed(
        "VOA Learning English · As It Is", "초급",
        "https://learningenglish.voanews.com/podcast/?count=20&zoneId=3521",
    ),
    PresetFeed(
        "VOA Learning English · American Stories", "초급",
        "https://learningenglish.voanews.com/podcast/?count=20&zoneId=1581",
    ),
    PresetFeed(
        "BBC · 6 Minute English", "중급",
        "https://podcasts.files.bbci.co.uk/p02pc9tn.rss",
    ),
    PresetFeed(
        "NPR · Planet Money", "고급",
        "https://feeds.npr.org/510289/podcast.xml",
    ),
)
