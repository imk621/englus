package com.englus.app.ui.podcast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.DownloadState
import com.englus.app.data.FeedItem
import com.englus.app.data.Podcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionsUi(
    val busy: String? = null,
    val error: String? = null,
    val url: String = "",
)

class SubscriptionsViewModel(app: Application) : AndroidViewModel(app) {

    private val feeds = (app as EnglusApp).feeds

    val podcasts: StateFlow<List<Podcast>> =
        feeds.observePodcasts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(SubscriptionsUi())
    val ui: StateFlow<SubscriptionsUi> = _ui.asStateFlow()

    fun setUrl(v: String) = _ui.update { it.copy(url = v) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    fun subscribe(feedUrl: String) {
        if (feedUrl.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "피드 가져오는 중…", error = null) }
            try {
                feeds.subscribe(feedUrl)
                _ui.update { it.copy(busy = null, url = "") }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, error = "구독 실패: ${e.message}") }
            }
        }
    }

    fun unsubscribe(podcast: Podcast) {
        viewModelScope.launch { feeds.unsubscribe(podcast.id) }
    }
}

data class PodcastUi(
    val refreshing: Boolean = false,
    val error: String? = null,
)

class PodcastViewModel(app: Application, private val podcastId: Long) : AndroidViewModel(app) {

    private val feeds = (app as EnglusApp).feeds

    val podcast: StateFlow<Podcast?> =
        feeds.observePodcast(podcastId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val items: StateFlow<List<FeedItem>> =
        feeds.observeItems(podcastId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(PodcastUi())
    val ui: StateFlow<PodcastUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true, error = null) }
            try {
                feeds.refresh(podcastId)
                _ui.update { it.copy(refreshing = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(refreshing = false, error = "새로고침 실패: ${e.message}") }
            }
        }
    }

    fun download(item: FeedItem) {
        if (item.downloadState == DownloadState.DOWNLOADING || item.episodeId != null) return
        viewModelScope.launch {
            feeds.markQueued(item.id)
            feeds.download(item.id)
        }
    }
}
