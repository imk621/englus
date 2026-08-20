package com.englus.app.ui.srs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.DueCard
import com.englus.app.srs.Rating
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SessionUi(
    val loading: Boolean = true,
    val index: Int = 0,
    val total: Int = 0,
    val revealed: Boolean = false,
    val playing: Boolean = false,
    val intervals: Map<Rating, Int> = emptyMap(),
    val done: Boolean = false,
    val reviewed: Int = 0,
)

/**
 * A review session over the cards due now.
 *
 * The card front is the audio, not the text. For a listening app a card you can
 * read is not a review of the thing that failed — the sentence was missed by ear,
 * so it has to come back by ear, in the original voice at the original speed.
 */
class ReviewSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val srs = (app as EnglusApp).srs

    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    private val _card = MutableStateFlow<DueCard?>(null)
    val card: StateFlow<DueCard?> = _card.asStateFlow()

    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private var loadedPath: String? = null
    private var clipEndMs = Long.MAX_VALUE

    private var queue: List<DueCard> = emptyList()

    init {
        viewModelScope.launch {
            queue = srs.loadDue()
            _ui.update { it.copy(loading = false, total = queue.size, done = queue.isEmpty()) }
            show(0)
        }
        viewModelScope.launch { watchClipEnd() }
    }

    private fun show(index: Int) {
        if (index > queue.lastIndex) {
            player.pause()
            _card.value = null
            _ui.update { it.copy(done = true) }
            return
        }
        val due = queue[index]
        _card.value = due
        _ui.update { it.copy(index = index, revealed = false, intervals = srs.preview(due.card)) }
        play()
    }

    fun play() {
        val due = _card.value ?: return
        val file = File(due.audioPath)
        if (!file.exists()) return
        if (loadedPath != due.audioPath) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            loadedPath = due.audioPath
        }
        clipEndMs = due.endMs + syncOffsetMs + TAIL_PAD_MS
        player.seekTo((due.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0))
        player.play()
    }

    private suspend fun watchClipEnd() {
        while (true) {
            if (player.isPlaying && player.currentPosition >= clipEndMs) player.pause()
            _ui.update { it.copy(playing = player.isPlaying) }
            delay(50)
        }
    }

    fun reveal() = _ui.update { it.copy(revealed = true) }

    fun grade(rating: Rating) {
        val due = _card.value ?: return
        viewModelScope.launch {
            srs.grade(due.card, rating)
            _ui.update { it.copy(reviewed = it.reviewed + 1) }
            show(_ui.value.index + 1)
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val HEAD_PAD_MS = 150
        const val TAIL_PAD_MS = 350
    }
}
