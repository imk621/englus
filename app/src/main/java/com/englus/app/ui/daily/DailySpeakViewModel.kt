package com.englus.app.ui.daily

import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.DailySentence
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.WordDiff
import com.englus.app.playback.PlaybackConnection
import com.englus.asr.AudioDecoder
import com.englus.asr.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class DailySpeakUi(
    val sentence: DailySentence? = null,
    val recording: Boolean = false,
    val scoring: Boolean = false,
    val revealed: Boolean = false,
    val diff: DiffResult? = null,
    val recognized: String? = null,
    val error: String? = null,
    val modelMissing: Boolean = false,
)

/**
 * Saying today's sentence out loud.
 *
 * Shadowing gives the learner the sound first and asks them to copy it; this
 * does not. They are shown the Korean and have to produce the English
 * themselves, which is the harder half and the one a podcast cannot set up
 * around their specific gaps.
 *
 * The same whisper model that transcribes their episodes listens back, so what
 * comes out is what an English listener would have heard - not a score from a
 * pronunciation service with its own opinion.
 */
class DailySpeakViewModel(app: Application, private val sentenceId: Long) : AndroidViewModel(app) {

    private val daily = (app as EnglusApp).daily
    private val models = (app as EnglusApp).models

    private val _ui = MutableStateFlow(DailySpeakUi())
    val ui: StateFlow<DailySpeakUi> = _ui.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var whisperPtr = 0L

    init {
        viewModelScope.launch {
            _ui.update { it.copy(sentence = daily.get(sentenceId)) }
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
    }

    fun startRecording() {
        if (_ui.value.recording) return
        PlaybackConnection.pause()
        val out = File(getApplication<Application>().cacheDir, "daily-${System.currentTimeMillis()}.m4a")
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
            _ui.update { it.copy(recording = true, error = null, diff = null, recognized = null) }
        } catch (e: Throwable) {
            out.delete()
            _ui.update { it.copy(recording = false, error = "녹음을 시작할 수 없습니다: ${e.message}") }
        }
    }

    fun stopAndScore() {
        if (!_ui.value.recording) return
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        _ui.update { it.copy(recording = false, scoring = true) }

        val file = recordFile
        val sentence = _ui.value.sentence
        if (file == null || sentence == null) {
            _ui.update { it.copy(scoring = false) }
            return
        }

        viewModelScope.launch {
            try {
                val recognized = withContext(Dispatchers.Default) { transcribe(file) }
                val diff = WordDiff.compare(sentence.english, recognized)
                _ui.update {
                    it.copy(
                        scoring = false,
                        diff = diff,
                        recognized = recognized,
                        // The model answer is only shown once they have committed
                        // to an attempt; before that it would just be read aloud.
                        revealed = true,
                    )
                }
                daily.markSpoken(sentence.id, diff.accuracy)
            } catch (e: Throwable) {
                _ui.update { it.copy(scoring = false, error = e.message ?: e.toString()) }
            } finally {
                file.delete()
                recordFile = null
            }
        }
    }

    /** Gives up on producing it and just looks - still counts as attempted. */
    fun reveal() {
        val sentence = _ui.value.sentence ?: return
        _ui.update { it.copy(revealed = true) }
        viewModelScope.launch { daily.markSpoken(sentence.id, 0f) }
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

    override fun onCleared() {
        runCatching { recorder?.release() }
        if (whisperPtr != 0L) WhisperNative.freeContext(whisperPtr)
        super.onCleared()
    }
}
