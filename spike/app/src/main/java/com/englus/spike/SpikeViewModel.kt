package com.englus.spike

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Token(val text: String, val t0: Long, val t1: Long, val tdtw: Long?, val p: Float)

data class Segment(val t0: Long, val t1: Long, val text: String, val tokens: List<Token>)

/** Everything Phase 0 needs to decide go / no-go, in one object. */
data class RunResult(
    val audioName: String,
    val modelName: String,
    val threads: Int,
    val useDtw: Boolean,
    val aheadsLabel: String,
    val audioSec: Double,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val decodeMs: Long,
    val modelLoadMs: Long,
    val transcribeMs: Long,
    val batteryStartPct: Int,
    val batteryEndPct: Int,
    val tempStartC: Float,
    val tempEndC: Float,
    val nativeTimings: String,
    val segments: List<Segment>,
) {
    /** < 1.0 means faster than realtime. The single number this spike is about. */
    val rtf: Double get() = if (audioSec > 0) (transcribeMs / 1000.0) / audioSec else 0.0
    val speedX: Double get() = if (transcribeMs > 0) audioSec / (transcribeMs / 1000.0) else 0.0

    /** Phase 0 target: a 10-minute episode transcribed in under 60 s. */
    val projected10MinSec: Double get() = 600.0 * rtf
    val meetsTarget: Boolean get() = projected10MinSec in 0.0..60.0

    val tokenCount: Int get() = segments.sumOf { it.tokens.size }
    val text: String get() = segments.joinToString(" ") { it.text.trim() }
}

data class UiState(
    val models: List<File> = emptyList(),
    val selectedModel: File? = null,
    val audioUri: Uri? = null,
    val audioName: String = "",
    val threads: Int = 4,
    val maxThreads: Int = 8,
    val useDtw: Boolean = true,
    val maxLen: Int = 0,
    val splitOnWord: Boolean = true,
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val result: RunResult? = null,
    val savedPath: String? = null,
    val systemInfo: String = "",
)

/** First 4 bytes of a ggml file: GGML_FILE_MAGIC 0x67676d6c, little-endian. */
private val GGML_MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)

class SpikeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val modelsDir: File = File(getApplication<Application>().getExternalFilesDir(null), "models")
    private val resultsDir: File = File(getApplication<Application>().getExternalFilesDir(null), "results")

    init {
        modelsDir.mkdirs()
        resultsDir.mkdirs()
        val cores = Runtime.getRuntime().availableProcessors()
        _state.update {
            it.copy(
                maxThreads = cores,
                // Big cores only. Oversubscribing to little cores measurably hurts.
                threads = (cores / 2).coerceIn(1, 8),
                systemInfo = runCatching { WhisperNative.systemInfo() }.getOrElse { e -> "n/a (${e.message})" },
            )
        }
        refreshModels()
    }

    /**
     * Scans `files/models/` and `files/` itself.
     *
     * The second location is not redundant: a directory created by
     * `adb shell mkdir` is owned by shell (uid 2000, mode 770) and the app gets
     * EACCES listing it even though it sits inside the app's own external dir.
     * Dropping the .bin straight into `files/` always works, so accept it there.
     */
    fun refreshModels() {
        val roots = listOfNotNull(modelsDir, getApplication<Application>().getExternalFilesDir(null))
        val found = roots
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toList().orEmpty() }
            .distinctBy { it.name }
            .sortedBy { it.name }
        _state.update { it.copy(models = found, selectedModel = it.selectedModel ?: found.firstOrNull()) }
    }

    fun selectModel(f: File) = _state.update { it.copy(selectedModel = f, result = null) }

    /**
     * Copies a .bin the user picked (typically from Downloads) into the app's
     * models directory.
     *
     * Without this the only way to install a model is `adb push`, because
     * Android 11+ blocks file managers from writing into Android/data/<pkg>.
     * Sideloading the APK is useless if the model cannot follow it.
     */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val name = queryDisplayName(uri).ifEmpty { "model.bin" }
            if (!name.endsWith(".bin")) {
                return@launch fail("whisper 모델은 .bin 파일이어야 합니다: $name")
            }

            _state.update { it.copy(running = true, stage = "모델 복사 중… ($name)", progress = 0f, error = null) }
            val dest = File(modelsDir, name)
            try {
                withContext(Dispatchers.IO) {
                    modelsDir.mkdirs()
                    val total = querySize(uri)
                    val app = getApplication<Application>()
                    app.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "파일을 열 수 없습니다" }
                        dest.outputStream().use { output ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) progress(copied.toFloat() / total)
                            }
                        }
                    }
                    // A truncated or wrong file otherwise surfaces later as an
                    // opaque "model failed to load" during a benchmark run.
                    val magic = ByteArray(4)
                    dest.inputStream().use { it.read(magic) }
                    if (!magic.contentEquals(GGML_MAGIC)) {
                        dest.delete()
                        error("ggml 모델 파일이 아닙니다 (매직 불일치)")
                    }
                }
                _state.update { it.copy(running = false, stage = "", progress = 1f) }
                refreshModels()
                _state.update { it.copy(selectedModel = dest) }
            } catch (e: Throwable) {
                dest.delete()
                _state.update { it.copy(running = false, stage = "", error = "모델 가져오기 실패: ${e.message}") }
            }
        }
    }
    fun setThreads(n: Int) = _state.update { it.copy(threads = n) }
    fun setDtw(v: Boolean) = _state.update { it.copy(useDtw = v) }
    fun setSplitOnWord(v: Boolean) = _state.update { it.copy(splitOnWord = v) }
    fun setMaxLen(v: Int) = _state.update { it.copy(maxLen = v) }

    fun setAudio(uri: Uri) {
        val name = queryDisplayName(uri)
        _state.update { it.copy(audioUri = uri, audioName = name, result = null, error = null) }
    }

    fun run() {
        val s = _state.value
        val model = s.selectedModel ?: return fail("모델(.bin)을 먼저 선택하세요")
        val uri = s.audioUri ?: return fail("오디오 파일을 먼저 선택하세요")

        _state.update { it.copy(running = true, error = null, result = null, savedPath = null, progress = 0f) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { transcribe(model, uri, s) }
                _state.update { it.copy(running = false, stage = "", progress = 1f, result = result) }
            } catch (e: Throwable) {
                _state.update { it.copy(running = false, stage = "", error = e.message ?: e.toString()) }
            }
        }
    }

    private suspend fun transcribe(model: File, uri: Uri, s: UiState): RunResult {
        val ctxApp = getApplication<Application>()
        val (battStart, tempStart) = batterySnapshot()

        stage("오디오 디코딩 중…")
        val pcm = AudioDecoder.decode(ctxApp, uri) { p -> progress(p * 0.15f) }

        stage("모델 로딩 중… (${model.name})")
        val aheads = WhisperNative.Aheads.forModelFile(model.name)
        val loadStart = System.currentTimeMillis()
        val ptr = WhisperNative.initContext(model.absolutePath, s.useDtw, aheads)
        val modelLoadMs = System.currentTimeMillis() - loadStart
        if (ptr == 0L) throw IllegalStateException("모델 로딩 실패: ${model.name}")

        try {
            stage("전사 중… (${"%.1f".format(pcm.durationSec)}초 오디오, ${s.threads} threads)")
            progress(0.2f)
            val t0 = System.currentTimeMillis()
            val json = WhisperNative.transcribe(
                ptr = ptr,
                audio = pcm.samples,
                nThreads = s.threads,
                language = "en",
                tokenTimestamps = true,
                useDtw = s.useDtw,
                maxLen = s.maxLen,
                splitOnWord = s.splitOnWord,
            )
            val transcribeMs = System.currentTimeMillis() - t0
            progress(1f)

            val root = JSONObject(json)
            if (!root.optBoolean("ok")) {
                throw IllegalStateException("전사 실패: ${root.optString("error", "unknown")}")
            }

            val (battEnd, tempEnd) = batterySnapshot()
            return RunResult(
                audioName = s.audioName,
                modelName = model.name,
                threads = s.threads,
                useDtw = s.useDtw,
                aheadsLabel = WhisperNative.Aheads.label(aheads),
                audioSec = pcm.durationSec,
                sourceSampleRate = pcm.sourceSampleRate,
                sourceChannels = pcm.sourceChannels,
                decodeMs = pcm.decodeMs,
                modelLoadMs = modelLoadMs,
                transcribeMs = transcribeMs,
                batteryStartPct = battStart,
                batteryEndPct = battEnd,
                tempStartC = tempStart,
                tempEndC = tempEnd,
                nativeTimings = root.optJSONObject("timings")?.toString() ?: "{}",
                segments = parseSegments(root),
            )
        } finally {
            WhisperNative.freeContext(ptr)
        }
    }

    private fun parseSegments(root: JSONObject): List<Segment> {
        val segs = root.optJSONArray("segments") ?: return emptyList()
        return (0 until segs.length()).map { i ->
            val o = segs.getJSONObject(i)
            val toks = o.optJSONArray("tokens")
            Segment(
                t0 = o.optLong("t0"),
                t1 = o.optLong("t1"),
                text = o.optString("text"),
                tokens = (0 until (toks?.length() ?: 0)).map { j ->
                    val t = toks!!.getJSONObject(j)
                    Token(
                        text = t.optString("text"),
                        t0 = t.optLong("t0"),
                        t1 = t.optLong("t1"),
                        tdtw = if (t.has("tdtw")) t.optLong("tdtw") else null,
                        p = t.optDouble("p", 0.0).toFloat(),
                    )
                },
            )
        }
    }

    /** Writes the run to external files dir so `adb pull` can grab it for WER scoring. */
    fun saveReport() {
        val r = _state.value.result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val stamp = System.currentTimeMillis()
                val base = "${r.modelName.removeSuffix(".bin")}_${r.threads}t${if (r.useDtw) "_dtw" else ""}_$stamp"

                File(resultsDir, "$base.json").writeText(buildJsonReport(r))
                // Plain text too: the WER script diffs this against a reference transcript.
                File(resultsDir, "$base.txt").writeText(r.text)

                _state.update { it.copy(savedPath = File(resultsDir, "$base.json").absolutePath) }
            }.onFailure { e -> _state.update { it.copy(error = "저장 실패: ${e.message}") } }
        }
    }

    private fun buildJsonReport(r: RunResult): String {
        val meta = JSONObject().apply {
            put("audio", r.audioName)
            put("model", r.modelName)
            put("threads", r.threads)
            put("dtw", r.useDtw)
            put("aheads", r.aheadsLabel)
            put("audioSec", r.audioSec)
            put("sourceSampleRate", r.sourceSampleRate)
            put("sourceChannels", r.sourceChannels)
            put("decodeMs", r.decodeMs)
            put("modelLoadMs", r.modelLoadMs)
            put("transcribeMs", r.transcribeMs)
            put("rtf", r.rtf)
            put("speedX", r.speedX)
            put("projected10MinSec", r.projected10MinSec)
            put("batteryPct", "${r.batteryStartPct} -> ${r.batteryEndPct}")
            put("tempC", "${r.tempStartC} -> ${r.tempEndC}")
            put("nativeTimings", JSONObject(r.nativeTimings))
            put("systemInfo", _state.value.systemInfo)
        }
        val segs = org.json.JSONArray()
        r.segments.forEach { s ->
            val toks = org.json.JSONArray()
            s.tokens.forEach { t ->
                toks.put(JSONObject().apply {
                    put("text", t.text); put("t0", t.t0); put("t1", t.t1)
                    t.tdtw?.let { put("tdtw", it) }
                    put("p", t.p.toDouble())
                })
            }
            segs.put(JSONObject().apply {
                put("t0", s.t0); put("t1", s.t1); put("text", s.text); put("tokens", toks)
            })
        }
        return JSONObject().apply { put("meta", meta); put("segments", segs) }.toString(2)
    }

    private fun batterySnapshot(): Pair<Int, Float> {
        val intent = getApplication<Application>()
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 0 to 0f
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        return (if (scale > 0) level * 100 / scale else level) to temp
    }

    private fun querySize(uri: Uri): Long =
        runCatching {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
            }
        }.getOrNull() ?: 0L

    private fun queryDisplayName(uri: Uri): String =
        runCatching {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun stage(s: String) = _state.update { it.copy(stage = s) }
    private fun progress(p: Float) = _state.update { it.copy(progress = p.coerceIn(0f, 1f)) }
    private fun fail(msg: String) = _state.update { it.copy(error = msg) }
}
