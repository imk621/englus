package com.englus.app.ui.shadowing

import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Sentence
import com.englus.app.data.SpeakingAttempt
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.WordDiff
import com.englus.asr.AudioDecoder
import com.englus.asr.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import com.englus.app.playback.PlaybackConnection

/** One sentence to shadow, with the audio it came from. */
data class ShadowItem(
    val sentence: Sentence,
    val audioPath: String,
    val episodeTitle: String,
)

data class ShadowUi(
    val index: Int = 0,
    val total: Int = 0,
    val playing: Boolean = false,
    val recording: Boolean = false,
    val scoring: Boolean = false,
    val diff: DiffResult? = null,
    val recognized: String? = null,
    val paceRatio: Float = 1f,
    val error: String? = null,
    val finished: Boolean = false,
    val modelMissing: Boolean = false,
    /** True when the queue is the learner's own missed sentences rather than an episode. */
    val savedMode: Boolean = false,
    val episodeTitle: String = "",
)

/**
 * Shadowing: hear it, say it, see how close you got.
 *
 * The same whisper model that produced the transcript listens back to the
 * learner, so the comparison is like for like — no cloud round trip, no separate
 * pronunciation service, and it works offline.
 */
/**
 * Shadowing, over an episode or over the sentences the learner actually missed.
 *
 * The second mode is the one that earns its keep. Walking an episode from
 * sentence one spends most of the session on lines that were already easy; the
 * app has known all along which sentences were failed, and those are the ones
 * worth saying out loud. It is the same argument the sound drills already make,
 * applied to speaking.
 *
 * A saved queue crosses episodes, so the audio file changes between sentences
 * and the player is loaded per item rather than once at the start.
 */
class ShadowingViewModel(
    app: Application,
    private val episodeId: Long?,
    startPosition: Int,
) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository
    private val models = (app as EnglusApp).models
    private val speakingDao = EnglusDatabase.get(app).speaking()
    private val episodeDao = EnglusDatabase.get(app).episodes()
    private val dictationDao = EnglusDatabase.get(app).dictation()

    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    private val _items = MutableStateFlow<List<ShadowItem>>(emptyList())
    val items: StateFlow<List<ShadowItem>> = _items.asStateFlow()

    private val _ui = MutableStateFlow(
        ShadowUi(index = if (episodeId == null) 0 else startPosition, savedMode = episodeId == null)
    )
    val ui: StateFlow<ShadowUi> = _ui.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private var loadedPath: String? = null
    private var clipEndMs = Long.MAX_VALUE

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartedAt = 0L

    /**
     * Kept open across attempts. Loading is only ~140 ms, but paying it on every
     * single sentence turns a quick drill into a stutter.
     */
    private var whisperPtr = 0L

    init {
        viewModelScope.launch {
            if (episodeId == null) loadSaved() else loadEpisode(startPosition)

            val model = models.preferred()
            if (model == null) {
                _ui.update { it.copy(modelMissing = true) }
            } else {
                withContext(Dispatchers.Default) {
                    whisperPtr = WhisperNative.initContext(
                        model.absolutePath, false, WhisperNative.Aheads.forModelFile(model.name),
                    )
                }
            }
        }
        viewModelScope.launch { watchClipEnd() }
    }

    private suspend fun loadEpisode(startPosition: Int) {
        val episode = episodeDao.get(episodeId ?: return) ?: return
        val list = repo.observeSentences(episode.id).first { it.isNotEmpty() }
        _items.value = list.map { ShadowItem(it, episode.audioPath, episode.title) }
        _ui.update {
            it.copy(
                total = list.size,
                index = startPosition.coerceIn(0, list.lastIndex),
                episodeTitle = episode.title,
            )
        }
        playCurrent()
    }

    /**
     * Only sentences that still have audio. A saved sentence from reading
     * material, or from an episode restored without its audio, has nothing to
     * imitate - putting it in the queue would just be a dead screen.
     */
    private suspend fun loadSaved() {
        val saved = dictationDao.observeSaved().first()
            .filter { it.audioPath.isNotBlank() && it.sentence.endMs > it.sentence.startMs }
            .map { ShadowItem(it.sentence, it.audioPath, it.episodeTitle) }
        _items.value = saved
        _ui.update { it.copy(total = saved.size, index = 0, finished = saved.isEmpty()) }
        if (saved.isNotEmpty()) playCurrent()
    }

    private fun current(): Sentence? = _items.value.getOrNull(_ui.value.index)?.sentence

    private suspend fun watchClipEnd() {
        while (true) {
            if (player.isPlaying && player.currentPosition >= clipEndMs) player.pause()
            _ui.update { it.copy(playing = player.isPlaying) }
            delay(50)
        }
    }

    fun playCurrent() {
        PlaybackConnection.pause()
        val item = _items.value.getOrNull(_ui.value.index) ?: return
        val file = File(item.audioPath)
        if (!file.exists()) return
        // The saved queue crosses episodes, so the file can change between one
        // sentence and the next.
        if (loadedPath != item.audioPath) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            loadedPath = item.audioPath
        }
        val s = item.sentence
        clipEndMs = s.endMs + syncOffsetMs + TAIL_PAD_MS
        player.playbackParameters = PlaybackParameters(1f)
        player.seekTo((s.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0))
        player.play()
    }

    fun startRecording() {
        if (_ui.value.recording) return
        player.pause()
        val out = File(getApplication<Application>().cacheDir, "shadow-${System.currentTimeMillis()}.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(getApplication())
            } else {
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = out
            recordStartedAt = System.currentTimeMillis()
            _ui.update { it.copy(recording = true, error = null, diff = null, recognized = null) }
        } catch (e: Throwable) {
            out.delete()
            _ui.update { it.copy(recording = false, error = "녹음을 시작할 수 없습니다: ${e.message}") }
        }
    }

    fun stopRecordingAndScore() {
        if (!_ui.value.recording) return
        val spokenMs = System.currentTimeMillis() - recordStartedAt
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        _ui.update { it.copy(recording = false, scoring = true) }

        val file = recordFile
        val sentence = current()
        if (file == null || sentence == null) {
            _ui.update { it.copy(scoring = false) }
            return
        }

        viewModelScope.launch {
            try {
                val recognized = withContext(Dispatchers.Default) { transcribe(file) }
                val diff = WordDiff.compare(sentence.text, recognized)
                val targetMs = (sentence.endMs - sentence.startMs).coerceAtLeast(1)

                _ui.update {
                    it.copy(
                        scoring = false,
                        diff = diff,
                        recognized = recognized,
                        paceRatio = spokenMs.toFloat() / targetMs,
                    )
                }

                speakingDao.insert(
                    SpeakingAttempt(
                        sentenceId = sentence.id,
                        episodeId = sentence.episodeId,
                        accuracy = diff.accuracy,
                        recognizedText = recognized,
                        targetText = sentence.text,
                        spokenMs = spokenMs,
                        targetMs = targetMs,
                        attemptedAt = System.currentTimeMillis(),
                    )
                )
                // A sentence you cannot say is a sentence to come back to.
                if (diff.accuracy < SAVE_THRESHOLD) repo.setSaved(sentence.id, true)
                // Positive evidence only: a miss here may be the recogniser's.
                (getApplication() as EnglusApp).vocab.ingest(diff, correctOnly = true)
            } catch (e: Throwable) {
                _ui.update { it.copy(scoring = false, error = e.message ?: e.toString()) }
            } finally {
                file.delete()
                recordFile = null
            }
        }
    }

    private suspend fun transcribe(file: File): String {
        if (whisperPtr == 0L) throw IllegalStateException("whisper 모델이 준비되지 않았습니다.")
        val pcm = AudioDecoder.decode(getApplication(), Uri.fromFile(file))
        val json = WhisperNative.transcribe(
            ptr = whisperPtr,
            audio = pcm.samples,
            nSamples = pcm.sampleCount,
            nThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8),
            language = "en",
            tokenTimestamps = false,
            useDtw = false,
            maxLen = 0,
            splitOnWord = false,
        )
        val root = JSONObject(json)
        if (!root.optBoolean("ok")) throw IllegalStateException("전사 실패")
        val segments = root.optJSONArray("segments") ?: return ""
        return (0 until segments.length())
            .joinToString(" ") { segments.getJSONObject(it).optString("text").trim() }
            .trim()
    }

    fun next() {
        val list = _items.value
        val nextIndex = _ui.value.index + 1
        if (nextIndex > list.lastIndex) {
            player.pause()
            _ui.update { it.copy(finished = true) }
            return
        }
        _ui.update {
            it.copy(index = nextIndex, diff = null, recognized = null, error = null, paceRatio = 1f)
        }
        playCurrent()
    }

    override fun onCleared() {
        runCatching { recorder?.release() }
        recordFile?.delete()
        player.release()
        if (whisperPtr != 0L) WhisperNative.freeContext(whisperPtr)
        super.onCleared()
    }

    private companion object {
        const val HEAD_PAD_MS = 150
        const val TAIL_PAD_MS = 350
        /** Below this the learner could not reproduce the line; it goes to review. */
        const val SAVE_THRESHOLD = 0.7f
    }
}
