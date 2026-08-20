package com.englus.app.ui.dictation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.DictationAttempt
import com.englus.app.data.Diagnosis
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Sentence
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.WordDiff
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Phase {
    /** Listening and typing. */
    TYPING,

    /** Answer was wrong; offer a slow replay before revealing anything. */
    WRONG,

    /** Still wrong after the slow replay — the text is shown and the question asked. */
    ASK_MEANING,

    /** Scored and recorded. */
    DONE,
}

data class DictationUi(
    val index: Int = 0,
    val total: Int = 0,
    val phase: Phase = Phase.TYPING,
    val input: String = "",
    val slow: Boolean = false,
    val playing: Boolean = false,
    val diff: DiffResult? = null,
    val diagnosis: Diagnosis? = null,
    /** Shown only after scoring — seeing it earlier would answer the question. */
    val translation: String? = null,
    val explaining: Boolean = false,
    val explainError: String? = null,
    val finished: Boolean = false,
)

class DictationViewModel(
    app: Application,
    private val episodeId: Long,
    startPosition: Int,
) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository
    private val dictationDao = EnglusDatabase.get(app).dictation()
    private val explainRepo = (app as EnglusApp).explain
    private val episodeDao = EnglusDatabase.get(app).episodes()

    /** Shared with the player so a calibrated subtitle offset also trims clips. */
    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    val sentences: StateFlow<List<Sentence>> =
        repo.observeSentences(episodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(DictationUi(index = startPosition))
    val ui: StateFlow<DictationUi> = _ui.asStateFlow()

    val explainConfigured: Boolean get() = explainRepo.configured

    private val detailSentenceId = MutableStateFlow<Long?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val note: StateFlow<com.englus.app.data.SentenceNote?> =
        detailSentenceId.flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(null) else explainRepo.observeNote(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private var clipEndMs = Long.MAX_VALUE

    init {
        viewModelScope.launch {
            val episode = episodeDao.get(episodeId) ?: return@launch
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(episode.audioPath))))
            player.prepare()

            val list = sentences.first { it.isNotEmpty() }
            _ui.update { it.copy(total = list.size, index = startPosition.coerceIn(0, list.lastIndex)) }
            playCurrent()
        }
        viewModelScope.launch { watchClipEnd() }
    }

    private fun current(): Sentence? = sentences.value.getOrNull(_ui.value.index)

    /**
     * Plays only this sentence and stops. Polling for the end rather than using a
     * clipping media source keeps a single prepared player for the whole episode —
     * re-preparing per sentence would add a visible pause to every replay.
     */
    private suspend fun watchClipEnd() {
        while (true) {
            if (player.isPlaying && player.currentPosition >= clipEndMs) {
                player.pause()
            }
            _ui.update { it.copy(playing = player.isPlaying) }
            delay(50)
        }
    }

    fun playCurrent(slow: Boolean = _ui.value.slow) {
        val s = current() ?: return
        // Whisper's boundaries run a little early and can clip the final word, so
        // pad both ends; the head pad is harmless, the tail pad is not optional.
        val start = (s.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0)
        clipEndMs = s.endMs + syncOffsetMs + TAIL_PAD_MS
        player.playbackParameters = PlaybackParameters(if (slow) SLOW_SPEED else 1f)
        player.seekTo(start)
        player.play()
    }

    fun setInput(v: String) = _ui.update { it.copy(input = v) }

    fun check() {
        val s = current() ?: return
        val diff = WordDiff.compare(s.text, _ui.value.input)

        when {
            diff.isPerfect && _ui.value.slow -> finish(s, diff, Diagnosis.SPEED)
            diff.isPerfect -> finish(s, diff, Diagnosis.CORRECT)
            // Wrong at normal speed: offer the slow replay before showing anything.
            // Revealing the text first would destroy the SPEED signal entirely.
            !_ui.value.slow -> _ui.update { it.copy(phase = Phase.WRONG, diff = diff) }
            else -> _ui.update { it.copy(phase = Phase.ASK_MEANING, diff = diff) }
        }
    }

    fun retrySlowly() {
        _ui.update { it.copy(phase = Phase.TYPING, slow = true, diff = null) }
        playCurrent(slow = true)
    }

    /** Skips the slow replay and goes straight to the meaning question. */
    fun revealAnswer() = _ui.update { it.copy(phase = Phase.ASK_MEANING) }

    fun answerMeaning(knowsIt: Boolean) {
        val s = current() ?: return
        val diff = _ui.value.diff ?: return
        finish(s, diff, if (knowsIt) Diagnosis.SOUND else Diagnosis.VOCAB)
    }

    private fun finish(sentence: Sentence, diff: DiffResult, diagnosis: Diagnosis) {
        _ui.update {
            it.copy(
                phase = Phase.DONE,
                diff = diff,
                diagnosis = diagnosis,
                translation = sentence.translation,
            )
        }
        detailSentenceId.value = sentence.id
        viewModelScope.launch {
            dictationDao.insert(
                DictationAttempt(
                    sentenceId = sentence.id,
                    episodeId = episodeId,
                    userText = _ui.value.input,
                    correctText = sentence.text,
                    accuracy = diff.accuracy,
                    diagnosis = diagnosis,
                    usedSlowReplay = _ui.value.slow,
                    attemptedAt = System.currentTimeMillis(),
                )
            )
            // Anything missed goes to the review list automatically. Relying on the
            // learner to bookmark their own failures is exactly the step that gets
            // skipped.
            if (diagnosis != Diagnosis.CORRECT) repo.setSaved(sentence.id, true)
        }
    }

    /**
     * Asked for explicitly, and only after the attempt is scored. Sending every
     * sentence would burn the daily quota on lines the learner already understood.
     */
    fun explain() {
        val sentence = current() ?: return
        _ui.update { it.copy(explaining = true, explainError = null) }
        viewModelScope.launch {
            try {
                explainRepo.explain(sentence, _ui.value.diagnosis)
                _ui.update { it.copy(explaining = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(explaining = false, explainError = e.message ?: e.toString()) }
            }
        }
    }

    fun next() {
        val list = sentences.value
        val nextIndex = _ui.value.index + 1
        if (nextIndex > list.lastIndex) {
            player.pause()
            _ui.update { it.copy(finished = true) }
            return
        }
        _ui.update {
            it.copy(
                index = nextIndex,
                phase = Phase.TYPING,
                input = "",
                slow = false,
                diff = null,
                diagnosis = null,
                translation = null,
                explainError = null,
            )
        }
        detailSentenceId.value = null
        playCurrent(slow = false)
    }

    fun skip() {
        val s = current() ?: return
        _ui.update { it.copy(diff = WordDiff.compare(s.text, ""), phase = Phase.ASK_MEANING) }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val SLOW_SPEED = 0.7f
        const val HEAD_PAD_MS = 150
        const val TAIL_PAD_MS = 350
    }
}
