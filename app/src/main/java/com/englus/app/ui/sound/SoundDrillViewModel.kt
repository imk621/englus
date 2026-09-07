package com.englus.app.ui.sound

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.Diagnosis
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.SavedSentence
import com.englus.app.sound.PatternHit
import com.englus.app.sound.SoundPattern
import com.englus.app.sound.SoundPatternDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import com.englus.app.playback.PlaybackConnection

/** One drillable pattern with how much of the learner's own material it covers. */
data class PatternSummary(
    val pattern: SoundPattern,
    val total: Int,
    /** How many of those were specifically diagnosed as a sound failure. */
    val soundDiagnosed: Int,
)

data class DrillUi(
    val index: Int = 0,
    val total: Int = 0,
    val playing: Boolean = false,
    val revealed: Boolean = false,
    val slow: Boolean = false,
    val spans: List<String> = emptyList(),
    val finished: Boolean = false,
)

/**
 * Drills one sound pattern across the sentences the learner actually missed.
 *
 * The material is not generated: it is their own failures, filtered to the ones
 * that contain the transformation being practised. A drill made of invented
 * sentences would train the pattern in a voice and a context they will never
 * meet again.
 */
class SoundDrillViewModel(
    app: Application,
    private val patternName: String?,
) : AndroidViewModel(app) {

    private val dao = EnglusDatabase.get(app).dictation()

    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    private val saved: StateFlow<List<SavedSentence>> =
        dao.observeSaved().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Counts for the picker. */
    val summaries: StateFlow<List<PatternSummary>> = saved.map { list ->
        SoundPattern.entries.mapNotNull { pattern ->
            val matching = list.filter { pattern in SoundPatternDetector.patterns(it.sentence.text) }
            if (matching.isEmpty()) return@mapNotNull null
            PatternSummary(
                pattern = pattern,
                total = matching.size,
                soundDiagnosed = matching.count { it.diagnosis == Diagnosis.SOUND },
            )
        }.sortedByDescending { it.total }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(DrillUi())
    val ui: StateFlow<DrillUi> = _ui.asStateFlow()

    private val _current = MutableStateFlow<SavedSentence?>(null)
    val current: StateFlow<SavedSentence?> = _current.asStateFlow()

    val pattern: SoundPattern? =
        patternName?.let { n -> SoundPattern.entries.firstOrNull { it.name == n } }

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private var loadedPath: String? = null
    private var clipEndMs = Long.MAX_VALUE
    private var queue: List<SavedSentence> = emptyList()

    init {
        viewModelScope.launch { watchClipEnd() }
        if (pattern != null) {
            viewModelScope.launch {
                saved.collect { list ->
                    if (queue.isNotEmpty()) return@collect
                    queue = list.filter { pattern in SoundPatternDetector.patterns(it.sentence.text) }
                    _ui.update { it.copy(total = queue.size, finished = queue.isEmpty()) }
                    if (queue.isNotEmpty()) show(0)
                }
            }
        }
    }

    private fun show(index: Int) {
        if (index > queue.lastIndex) {
            player.pause()
            _current.value = null
            _ui.update { it.copy(finished = true) }
            return
        }
        val item = queue[index]
        _current.value = item
        _ui.update {
            it.copy(
                index = index,
                revealed = false,
                slow = false,
                spans = SoundPatternDetector.detect(item.sentence.text)
                    .firstOrNull { hit -> hit.pattern == pattern }
                    ?.spans.orEmpty(),
            )
        }
        play(slow = false)
    }

    fun play(slow: Boolean = _ui.value.slow) {
        PlaybackConnection.pause()
        val item = _current.value ?: return
        val file = File(item.audioPath)
        if (!file.exists()) return
        if (loadedPath != item.audioPath) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            loadedPath = item.audioPath
        }
        // Slow replay matters more here than anywhere else: a reduction is only
        // audible once it is stretched, and hearing it once slowly is what makes
        // it findable at speed.
        player.playbackParameters = PlaybackParameters(if (slow) 0.65f else 1f)
        clipEndMs = item.sentence.endMs + syncOffsetMs + TAIL_PAD_MS
        player.seekTo((item.sentence.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0))
        player.play()
        _ui.update { it.copy(slow = slow) }
    }

    private suspend fun watchClipEnd() {
        while (true) {
            if (player.isPlaying && player.currentPosition >= clipEndMs) player.pause()
            _ui.update { it.copy(playing = player.isPlaying) }
            delay(50)
        }
    }

    fun reveal() = _ui.update { it.copy(revealed = true) }

    fun next() = show(_ui.value.index + 1)

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val HEAD_PAD_MS = 150
        const val TAIL_PAD_MS = 350
    }
}
