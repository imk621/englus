package com.englus.app.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import com.englus.app.playback.PlaybackConnection
import com.englus.app.EnglusApp
import com.englus.app.data.Episode
import com.englus.app.data.Sentence
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUi(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    /** Loop the sentence being listened to — the core drill for a phrase you can't catch. */
    val repeatSentence: Boolean = false,
    /** Listen first, read second. Hiding the text is what makes this practice rather than reading. */
    val hideText: Boolean = true,
    val revealed: Set<Long> = emptySet(),
    /**
     * Korean is a third step, never a default. A translation sitting next to
     * every line turns intensive listening into reading Korean — the same
     * reason the English text starts hidden.
     */
    val translated: Set<Long> = emptySet(),
    val autoScroll: Boolean = true,
    /**
     * Milliseconds to delay the highlight relative to the audio.
     *
     * Whisper's segment start times tend to run early — leading silence gets
     * folded into the segment — so a word can light up before it is spoken. The
     * error is a property of the transcript, not of playback, so it is corrected
     * at display time rather than by re-transcribing.
     */
    val syncOffsetMs: Int = 0,
    val showSync: Boolean = false,
    val detailSentenceId: Long? = null,
    val explaining: Boolean = false,
    val explainError: String? = null,
)

class PlayerViewModel(
    app: Application,
    private val episodeId: Long,
) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository
    private val explainRepo = (app as EnglusApp).explain

    val episode: StateFlow<Episode?> =
        repo.observeEpisode(episodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sentences: StateFlow<List<Sentence>> =
        repo.observeSentences(episodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val prefs = app.getSharedPreferences("player", Application.MODE_PRIVATE)

    private val _ui = MutableStateFlow(PlayerUi(syncOffsetMs = prefs.getInt(KEY_SYNC, 0)))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    /**
     * The shared session player, not one of this screen's own.
     *
     * A player owned here would be released when the screen goes away, which is
     * exactly the moment the phone goes into a pocket and the listening should
     * carry on. Null until the connection is established, so every use is
     * guarded - the alternative is blocking the first frame on an IPC round trip.
     */
    private var player: Player? = null

    val explainConfigured: Boolean get() = explainRepo.configured

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val note: StateFlow<com.englus.app.data.SentenceNote?> =
        _ui.map { it.detailSentenceId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(null) else explainRepo.observeNote(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun toggleDetail(id: Long) = _ui.update {
        it.copy(
            detailSentenceId = if (it.detailSentenceId == id) null else id,
            explainError = null,
        )
    }

    /** No diagnosis here — this is curiosity while listening, not a scored miss. */
    fun explain(sentence: Sentence) {
        _ui.update { it.copy(explaining = true, explainError = null) }
        viewModelScope.launch {
            try {
                explainRepo.explain(sentence, null)
                _ui.update { it.copy(explaining = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(explaining = false, explainError = e.message ?: e.toString()) }
            }
        }
    }

    private var prepared = false

    init {
        viewModelScope.launch {
            val controller = PlaybackConnection.await(getApplication())
            player = controller
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _ui.update { it.copy(playing = isPlaying) }
                }
            })
            _ui.update { it.copy(playing = controller.isPlaying) }
        }
        viewModelScope.launch {
            repo.observeEpisode(episodeId).collect { ep ->
                if (ep != null && ep.translationState != com.englus.app.data.TranslationState.DONE) {
                    // Covers episodes transcribed before translation existed,
                    // and retries a pass that failed for want of network.
                    repo.enqueueTranslation(episodeId)
                }
                if (ep != null && !prepared) {
                    val controller = PlaybackConnection.await(getApplication())
                    prepared = true
                    // Re-opening the episode that is already playing must not
                    // restart it: coming back to the screen after a walk should
                    // land where the listening got to, not at zero.
                    val uri = Uri.fromFile(File(ep.audioPath)).toString()
                    val already = controller.currentMediaItem
                        ?.localConfiguration?.uri?.toString() == uri
                    if (!already) {
                        controller.setMediaItem(
                            MediaItem.Builder()
                                .setUri(uri)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(ep.title)
                                        .setArtist("englus")
                                        .build()
                                )
                                .build()
                        )
                        controller.prepare()
                    }
                }
            }
        }
        viewModelScope.launch { trackPosition() }
    }

    /**
     * Polls rather than relying on player callbacks: the karaoke highlight and
     * the sentence loop both need a position at word granularity, and ExoPlayer
     * only emits discrete events.
     */
    private suspend fun trackPosition() {
        while (true) {
            val pos = player?.currentPosition ?: 0L
            _ui.update { it.copy(positionMs = pos) }

            if (_ui.value.repeatSentence && player?.isPlaying == true) {
                val s = currentSentence(pos)
                if (s != null && transcriptTime(pos) >= s.endMs) player?.seekTo(mediaTime(s.startMs))
            }
            delay(50)
        }
    }

    /** Playback clock -> transcript clock. */
    private fun transcriptTime(positionMs: Long): Long = positionMs - _ui.value.syncOffsetMs

    /** Transcript clock -> playback clock, for seeking. */
    private fun mediaTime(timestampMs: Long): Long =
        (timestampMs + _ui.value.syncOffsetMs).coerceAtLeast(0)

    fun currentSentence(positionMs: Long = _ui.value.positionMs): Sentence? {
        val list = sentences.value
        if (list.isEmpty()) return null
        val target = transcriptTime(positionMs)
        // Binary search on start time; a linear scan per 50 ms tick over 500
        // sentences would be pointless work.
        var lo = 0
        var hi = list.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (list[mid].startMs <= target) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return list[found]
    }

    fun currentWordIndex(sentence: Sentence, positionMs: Long): Int {
        val target = transcriptTime(positionMs)
        val i = sentence.words.indexOfLast { it.startMs <= target }
        return if (i >= 0 && target <= sentence.words[i].endMs + WORD_HOLD_MS) i else -1
    }

    fun playPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekToSentence(s: Sentence) {
        val p = player ?: return
        p.seekTo(mediaTime(s.startMs))
        if (!p.isPlaying) p.play()
    }

    fun toggleSync() = _ui.update { it.copy(showSync = !it.showSync) }

    /** Positive offset shows the text later; whisper's starts run early, so that is the usual direction. */
    fun adjustSync(deltaMs: Int) = _ui.update {
        val next = (it.syncOffsetMs + deltaMs).coerceIn(-2000, 2000)
        prefs.edit().putInt(KEY_SYNC, next).apply()
        it.copy(syncOffsetMs = next)
    }

    fun resetSync() = _ui.update {
        prefs.edit().putInt(KEY_SYNC, 0).apply()
        it.copy(syncOffsetMs = 0)
    }

    fun step(delta: Int) {
        val list = sentences.value
        val cur = currentSentence() ?: return
        val next = (cur.position + delta).coerceIn(0, list.lastIndex)
        list.getOrNull(next)?.let { seekToSentence(it) }
    }

    fun setSpeed(v: Float) {
        player?.playbackParameters = PlaybackParameters(v)
        _ui.update { it.copy(speed = v) }
    }

    fun toggleRepeat() = _ui.update { it.copy(repeatSentence = !it.repeatSentence) }

    fun toggleHideText() = _ui.update {
        it.copy(hideText = !it.hideText, revealed = emptySet(), translated = emptySet())
    }

    fun reveal(id: Long) = _ui.update { it.copy(revealed = it.revealed + id) }

    fun toggleTranslation(id: Long) = _ui.update {
        it.copy(translated = if (id in it.translated) it.translated - id else it.translated + id)
    }

    fun toggleAutoScroll() = _ui.update { it.copy(autoScroll = !it.autoScroll) }

    fun toggleSaved(s: Sentence) {
        viewModelScope.launch { repo.setSaved(s.id, !s.saved) }
    }

    /**
     * The player is not released here. It belongs to the service, and the whole
     * point is that closing this screen does not stop the audio.
     */
    override fun onCleared() {
        player = null
        super.onCleared()
    }

    private companion object {
        /** Keeps the last word lit through the gap before the next one starts. */
        const val WORD_HOLD_MS = 250
        const val KEY_SYNC = "syncOffsetMs"
    }
}
