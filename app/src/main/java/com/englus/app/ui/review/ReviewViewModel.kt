package com.englus.app.ui.review

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.Diagnosis
import com.englus.app.data.DiagnosisCount
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.SavedSentence
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ReviewUi(
    val filter: Diagnosis? = null,
    val playingSentenceId: Long? = null,
    val expandedSentenceId: Long? = null,
    val explaining: Boolean = false,
    val explainError: String? = null,
)

class ReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository
    private val dao = EnglusDatabase.get(app).dictation()
    private val explainRepo = (app as EnglusApp).explain

    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    private val _ui = MutableStateFlow(ReviewUi())
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()

    val items: StateFlow<List<SavedSentence>> =
        combine(dao.observeSaved(), _ui) { saved, ui ->
            if (ui.filter == null) saved else saved.filter { it.diagnosis == ui.filter }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weakness: StateFlow<List<DiagnosisCount>> =
        dao.observeWeakness().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalAttempts: StateFlow<Int> =
        dao.observeTotalAttempts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val correctAttempts: StateFlow<Int> =
        dao.observeCorrectAttempts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private var loadedPath: String? = null
    private var clipEndMs = Long.MAX_VALUE

    init { viewModelScope.launch { watchClipEnd() } }

    fun setFilter(d: Diagnosis?) = _ui.update { it.copy(filter = d) }

    val explainConfigured: Boolean get() = explainRepo.configured

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val note: StateFlow<com.englus.app.data.SentenceNote?> =
        _ui.map { it.expandedSentenceId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(null) else explainRepo.observeNote(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun toggleExpanded(item: SavedSentence) = _ui.update {
        it.copy(
            expandedSentenceId = if (it.expandedSentenceId == item.sentence.id) null else item.sentence.id,
            explainError = null,
        )
    }

    fun explain(item: SavedSentence) {
        _ui.update { it.copy(explaining = true, explainError = null) }
        viewModelScope.launch {
            try {
                explainRepo.explain(item.sentence, item.diagnosis)
                _ui.update { it.copy(explaining = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(explaining = false, explainError = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Plays the saved sentence's own audio, not a synthesized reading. The card is
     * only worth reviewing if it sounds the way it did when it was missed —
     * same speaker, same speed, same reductions.
     */
    fun play(item: SavedSentence) {
        val path = item.audioPath
        if (!File(path).exists()) return
        if (loadedPath != path) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            player.prepare()
            loadedPath = path
        }
        clipEndMs = item.sentence.endMs + syncOffsetMs + TAIL_PAD_MS
        player.seekTo((item.sentence.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0))
        player.play()
        _ui.update { it.copy(playingSentenceId = item.sentence.id) }
    }

    private suspend fun watchClipEnd() {
        while (true) {
            if (player.isPlaying && player.currentPosition >= clipEndMs) {
                player.pause()
                _ui.update { it.copy(playingSentenceId = null) }
            }
            delay(50)
        }
    }

    fun remove(item: SavedSentence) {
        viewModelScope.launch { repo.setSaved(item.sentence.id, false) }
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
