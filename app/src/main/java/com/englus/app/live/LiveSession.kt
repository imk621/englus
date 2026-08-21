package com.englus.app.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

sealed interface LiveEvent {
    /** The model accepted the configuration; audio may start flowing. */
    data object Ready : LiveEvent

    /** A fragment of what the learner said, as the model heard it. */
    data class UserText(val text: String) : LiveEvent

    /** A fragment of what the model said. */
    data class ModelText(val text: String) : LiveEvent

    data object TurnComplete : LiveEvent

    /** The learner spoke over the model; queued audio is now stale. */
    data object Interrupted : LiveEvent

    data class Failed(val message: String) : LiveEvent

    data object Closed : LiveEvent
}

/**
 * One live audio conversation with Gemini.
 *
 * The transport is a WebSocket carrying JSON both ways; the audio is raw PCM,
 * 16 kHz going up and 24 kHz coming back, base64 inside those messages.
 *
 * Two details do most of the work here. The microphone is opened on
 * VOICE_COMMUNICATION with the platform echo canceller attached, because
 * otherwise the phone's own speaker feeds the model's voice straight back into
 * the microphone and it answers itself. And turn taking is left to the server's
 * voice activity detection rather than a press-to-talk button: being able to
 * interrupt, and being interrupted, is most of what makes speaking practice feel
 * like a conversation instead of a walkie-talkie.
 */
class LiveSession(
    private val context: Context,
    private val apiKey: String,
    private val model: String,
    private val systemInstruction: String,
    private val voice: String = DEFAULT_VOICE,
) {

    private val _events = MutableSharedFlow<LiveEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null
    private var playJob: Job? = null

    @Volatile private var running = false
    @Volatile private var ready = false
    private var watchdog: Job? = null
    private val playback = LinkedBlockingQueue<ByteArray>()

    private val client = OkHttpClient.Builder()
        // The socket is idle whenever nobody is speaking, which is most of a
        // conversation. Without a ping the connection is dropped mid-pause.
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun start() {
        if (running) return
        running = true
        val request = Request.Builder().url("$ENDPOINT?key=$apiKey").build()
        socket = client.newWebSocket(request, Listener())
    }

    fun stop() {
        if (!running) return
        running = false
        ready = false
        watchdog?.cancel()
        captureJob?.cancel()
        playJob?.cancel()
        playback.clear()
        runCatching { socket?.close(1000, null) }
        socket = null
        releaseAudio()
        scope.launch { _events.emit(LiveEvent.Closed) }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupMessage())
            // The upgrade succeeding does not mean the session will start. With a
            // bad key the server accepts the socket and then says nothing at all,
            // which without this left the screen on "connecting" until the ping
            // timeout fired twenty seconds later and reported a pong failure -
            // true, useless, and in English.
            watchdog = scope.launch {
                kotlinx.coroutines.delay(SETUP_TIMEOUT_MS)
                if (!ready && running) {
                    emit(
                        LiveEvent.Failed(
                            "서버가 응답하지 않습니다. API 키가 유효한지, " +
                                "설정의 Live 모델 이름이 맞는지 확인하세요."
                        )
                    )
                    stop()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        /**
         * The server sends its JSON as a binary frame. Reading only the text
         * callback produced a session that connected, billed, and never
         * appeared to say anything.
         */
        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
            handle(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            emit(LiveEvent.Failed(describe(t, response)))
            stop()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running) emit(LiveEvent.Failed(if (reason.isBlank()) "연결이 끊겼습니다 ($code)" else redact(reason)))
            stop()
        }
    }

    private fun handle(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return

        // has(), not optJSONObject(): the server sends this as an empty object,
        // and an empty object is still the signal.
        if (root.has("setupComplete")) {
            ready = true
            watchdog?.cancel()
            startAudio()
            emit(LiveEvent.Ready)
            return
        }
        root.optJSONObject("error")?.let {
            emit(LiveEvent.Failed(it.optString("message", "알 수 없는 오류")))
            stop()
            return
        }
        // A session has a wall-clock limit; the server says so before cutting it.
        root.optJSONObject("goAway")?.let {
            emit(LiveEvent.Failed("세션 시간이 곧 끝납니다."))
            return
        }

        val content = root.optJSONObject("serverContent") ?: return

        content.optJSONObject("inputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }?.let { emit(LiveEvent.UserText(it)) }
        content.optJSONObject("outputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }?.let { emit(LiveEvent.ModelText(it)) }

        if (content.optBoolean("interrupted")) {
            // Everything queued was going to be spoken over. Dropping it is the
            // difference between interrupting the model and talking across it.
            playback.clear()
            runCatching { track?.flush() }
            emit(LiveEvent.Interrupted)
        }

        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    ?.optString("data").orEmpty()
                if (data.isNotEmpty()) {
                    runCatching { Base64.decode(data, Base64.DEFAULT) }
                        .getOrNull()?.let { playback.offer(it) }
                }
            }
        }

        if (content.optBoolean("turnComplete")) emit(LiveEvent.TurnComplete)
    }

    private fun setupMessage(): String = JSONObject().put(
        "setup",
        JSONObject().apply {
            put("model", "models/$model")
            put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voice),
                            )
                        )
                    )
            )
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            // Both directions, and this is not optional for a learning app: audio
            // that is not written down cannot be reviewed, corrected, or turned
            // into cards. Without it the session teaches nothing after it ends.
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        }
    ).toString()

    private fun emit(event: LiveEvent) {
        scope.launch { _events.emit(event) }
    }

    /**
     * A message the learner can act on.
     *
     * The library's own text is written for whoever is reading a stack trace:
     * "sent ping but didn't receive pong within 20000ms" is accurate and tells
     * someone holding a phone nothing. The original is kept on the end for the
     * cases not recognised here, with the key stripped out of it - the key
     * travels as a query parameter and would otherwise ride along into anything
     * that echoes the request.
     */
    private fun describe(t: Throwable, response: Response?): String {
        val code = response?.code
        return when {
            code == 401 || code == 403 -> "API 키가 거부됐습니다. 설정에서 키를 확인하세요."
            code == 404 -> "Live 모델을 찾을 수 없습니다. 설정에서 모델 이름을 확인하세요."
            code == 429 -> "요청 한도를 넘었습니다. 잠시 후 다시 시도하세요."
            code != null && code >= 500 -> "서버 오류입니다 ($code). 잠시 후 다시 시도하세요."
            t is java.net.UnknownHostException -> "네트워크에 연결할 수 없습니다."
            t is java.net.SocketTimeoutException -> "응답이 없어 연결이 끊겼습니다."
            else -> "연결이 끊겼습니다: " + redact(t.message ?: t.toString())
        }
    }

    private fun redact(message: String): String =
        message.replace(Regex("key=[A-Za-z0-9_-]+"), "key=***")

    private fun startAudio() {
        if (recorder != null) return
        runCatching { openMicrophone() }.onFailure {
            emit(LiveEvent.Failed("마이크를 열 수 없습니다."))
            stop()
            return
        }
        openSpeaker()

        captureJob = scope.launch { capture() }
        playJob = scope.launch { play() }
    }

    /**
     * VOICE_COMMUNICATION, not MIC.
     *
     * On this source the platform runs its echo canceller against what the
     * speaker is playing. Recording from MIC instead sends the model's own voice
     * back to it, and it answers itself in a loop that sounds like the app is
     * broken. The effects below are belt and braces on top of that: they are not
     * present on every device, so neither is trusted alone.
     */
    private fun openMicrophone() {
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(CHUNK_BYTES * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            suppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        record.startRecording()
        recorder = record
    }

    private fun openSpeaker() {
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(OUTPUT_RATE)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Marks this as the far end of a call, which is what puts it
                    // on the same path the echo canceller uses as its reference.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }

        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode =
                AudioManager.MODE_IN_COMMUNICATION
        }
    }

    /**
     * Streams the microphone continuously.
     *
     * There is no push-to-talk and no local silence gate: the server's voice
     * activity detection decides where a turn ends, and a client-side gate would
     * clip the first syllable of every sentence - the part a learner most needs
     * heard correctly.
     */
    private suspend fun capture() {
        val buffer = ByteArray(CHUNK_BYTES)
        while (scope.isActive && running) {
            val read = recorder?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue
            _micLevel.value = level(buffer, read)

            val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
            val message = JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
                        .put("data", encoded),
                )
            ).toString()
            if (socket?.send(message) != true) break
        }
    }

    private suspend fun play() {
        while (scope.isActive && running) {
            val chunk = playback.poll(100, TimeUnit.MILLISECONDS) ?: continue
            var offset = 0
            while (offset < chunk.size && running) {
                val written = track?.write(chunk, offset, chunk.size - offset) ?: break
                if (written <= 0) break
                offset += written
            }
        }
    }

    /** Peak amplitude, 0..1, for the level meter. */
    private fun level(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            val magnitude = kotlin.math.abs(sample.toInt())
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }

    private fun releaseAudio() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { aec?.release() }
        aec = null
        runCatching { suppressor?.release() }
        suppressor = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode =
                AudioManager.MODE_NORMAL
        }
        _micLevel.value = 0f
    }

    companion object {
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Fixed by the API: 16 kHz up, 24 kHz down, both mono 16-bit PCM. */
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000

        /** 100 ms per message: fewer frames than 20 ms chunks, no audible lag. */
        const val CHUNK_BYTES = INPUT_RATE / 10 * 2

        const val DEFAULT_VOICE = "Puck"

        /**
         * How long to wait for the session to be accepted after the socket
         * opens. Short enough that a wrong key is a wrong key rather than a
         * hang, long enough for a slow mobile connection.
         */
        const val SETUP_TIMEOUT_MS = 10_000L
    }
}
