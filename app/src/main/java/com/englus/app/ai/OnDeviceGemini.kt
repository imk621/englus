package com.englus.app.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach

/**
 * Gemini Nano through AICore (ML Kit GenAI Prompt API).
 *
 * The S25 ships AICore, and Nano runs on its NPU: free, offline, nothing
 * leaves the phone, and no key to paste. Before this, every explanation,
 * correction and daily sentence needed a Gemini key and a network round trip,
 * so the app's "why" layer was missing for anyone who had not set one up.
 *
 * Limits that shape the design:
 * - Emulators and most phones report UNAVAILABLE. Every caller must keep a
 *   path that works without it ([AiRouter] falls back to the cloud).
 * - AICore rejects inference from the background, so the daily worker cannot
 *   use it. Foreground calls only.
 * - No schema enforcement: [JsonText] asks for the shape and checks it.
 */
object OnDeviceGemini : TextModel {

    enum class Status(val label: String) {
        UNKNOWN("확인 중"),
        UNAVAILABLE("이 기기에서 지원하지 않음"),
        DOWNLOADABLE("모델 내려받기 필요"),
        DOWNLOADING("모델 내려받는 중"),
        AVAILABLE("사용 가능"),
    }

    override val modelId = "gemini-nano"
    override val onDevice = true

    private val _status = MutableStateFlow(Status.UNKNOWN)
    /** Last known status; [refresh] updates it. Read synchronously by `configured` checks. */
    val status: StateFlow<Status> = _status.asStateFlow()

    // Created on first use: on a phone without AICore, merely building the
    // client is harmless, but there is no reason to pay for it at startup.
    private val model: GenerativeModel by lazy { Generation.getClient() }

    suspend fun refresh(): Status {
        val s = runCatching {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
                else -> Status.UNAVAILABLE
            }
        }.getOrElse {
            Log.i(TAG, "checkStatus failed: ${it.message}")
            Status.UNAVAILABLE
        }
        _status.value = s
        return s
    }

    /** Starts (or joins) the AICore download. Completion flips [status] to AVAILABLE. */
    fun download(): Flow<DownloadStatus> = model.download()
        .onEach {
            when (it) {
                is DownloadStatus.DownloadCompleted -> _status.value = Status.AVAILABLE
                is DownloadStatus.DownloadFailed -> _status.value = Status.DOWNLOADABLE
                else -> _status.value = Status.DOWNLOADING
            }
        }
        .catch { _status.value = Status.UNAVAILABLE; throw it }

    override suspend fun generate(request: TextRequest): String {
        if (refresh() != Status.AVAILABLE) {
            throw AiException("기기 안 모델(Gemini Nano)을 쓸 수 없습니다: ${_status.value.label}")
        }
        val prompt = JsonText.onDevicePrompt(request.prompt, request.schema)

        val reply = runCatching { run(prompt, request, request.maxOutputTokens) }
            // Nano's output cap is lower than the cloud's and differs by device.
            // Rather than hard-coding one device's number, retry once with the
            // library default when the requested cap is refused.
            .recoverCatching { run(prompt, request, null) }
            .getOrElse { throw AiException("기기 안 모델 오류: ${it.message}", it) }

        val schema = request.schema ?: return reply.trim()
        val json = JsonText.extract(reply)
            ?: throw AiException("기기 안 모델이 형식에 맞지 않는 답을 냈습니다.")
        if (!JsonText.satisfies(json, schema)) {
            throw AiException("기기 안 모델의 답에 빠진 항목이 있습니다.")
        }
        return json.toString()
    }

    private suspend fun run(prompt: String, request: TextRequest, maxTokens: Int?): String {
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = request.temperature
                candidateCount = 1
                if (maxTokens != null) maxOutputTokens = maxTokens
            }
        )
        return response.candidates.firstOrNull()?.text
            ?: throw AiException("기기 안 모델이 빈 응답을 반환했습니다.")
    }

    private const val TAG = "OnDeviceGemini"
}
