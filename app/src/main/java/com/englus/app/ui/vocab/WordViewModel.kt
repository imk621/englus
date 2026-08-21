package com.englus.app.ui.vocab

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.SavedSentence
import com.englus.app.data.VocabWord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class WordUi(
    val lemma: String = "",
    val word: VocabWord? = null,
    val sentences: List<SavedSentence> = emptyList(),
    val loading: Boolean = true,
    val playingId: Long? = null,
    val savedIds: Set<Long> = emptySet(),
)

/**
 * One unknown word, shown as the sentences it was met in.
 *
 * This is the half of the vocabulary profile that was missing. Measuring which
 * words a learner fails is only useful if it leads somewhere, and the place it
 * should lead is back to their own audio - not to a word list, which teaches the
 * word in a context they will never encounter again.
 */
class WordViewModel(app: Application, private val lemma: String) : AndroidViewModel(app) {

    private val vocab = (app as EnglusApp).vocab
    private val repo = (app as EnglusApp).repository

    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    private val _ui = MutableStateFlow(WordUi(lemma = lemma))
    val ui: StateFlow<WordUi> = _ui.asStateFlow()

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private var loadedPath: String? = null
    private var clipEndMs = Long.MAX_VALUE

    init {
        viewModelScope.launch {
            val found = vocab.sentencesFor(lemma)
            _ui.update {
                it.copy(
                    loading = false,
                    word = vocab.word(lemma),
                    sentences = found,
                    savedIds = found.filter { s -> s.sentence.saved }.map { s -> s.sentence.id }.toSet(),
                )
            }
        }
        viewModelScope.launch { watchClipEnd() }
    }

    fun play(item: SavedSentence, slow: Boolean = false) {
        val file = File(item.audioPath)
        // Reading material and restored-without-audio episodes have no file.
        if (item.audioPath.isBlank() || !file.exists()) return
        if (loadedPath != item.audioPath) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            loadedPath = item.audioPath
        }
        player.playbackParameters = PlaybackParameters(if (slow) SLOW_SPEED else 1f)
        clipEndMs = item.sentence.endMs + syncOffsetMs + TAIL_PAD_MS
        player.seekTo((item.sentence.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0))
        player.play()
        _ui.update { it.copy(playingId = item.sentence.id) }
    }

    fun toggleSaved(item: SavedSentence) {
        val id = item.sentence.id
        val nowSaved = id !in _ui.value.savedIds
        _ui.update {
            it.copy(savedIds = if (nowSaved) it.savedIds + id else it.savedIds - id)
        }
        // setSaved also creates the review card, so this is the whole path from
        // "I do not know this word" to "it is scheduled".
        viewModelScope.launch { repo.setSaved(id, nowSaved) }
    }

    fun markKnown() {
        viewModelScope.launch {
            vocab.setKnown(lemma, true)
            _ui.update { it.copy(word = vocab.word(lemma)) }
        }
    }

    private suspend fun watchClipEnd() {
        while (true) {
            if (player.isPlaying && player.currentPosition >= clipEndMs) {
                player.pause()
                _ui.update { it.copy(playingId = null) }
            }
            delay(50)
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val SLOW_SPEED = 0.65f
        const val HEAD_PAD_MS = 150
        const val TAIL_PAD_MS = 350
    }
}
