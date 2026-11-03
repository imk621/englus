package com.englus.app.ai

import android.content.Context
import android.util.Log
import com.englus.app.gemini.GeminiSettings

/**
 * Picks the model for each request: Gemini Nano on the phone, the Gemini API
 * in the cloud, or one then the other.
 *
 * Hybrid on-device-first is the point. Nano is free, offline and private, so
 * it should take everything it can do well; the cloud model is the fallback
 * for a phone without AICore, a reply Nano got wrong, or a task Nano is not
 * trusted with ([AiTask.onDeviceFirst]).
 */
class AiRouter(context: Context) {

    val settings = GeminiSettings(context)
    val cloud = CloudGemini(settings)
    private val local: OnDeviceGemini = OnDeviceGemini

    /**
     * Whether [task] can run at all with what is set up now. Synchronous for
     * screens, so it reads the last known on-device status rather than asking.
     */
    fun configured(task: AiTask = AiTask.EXPLAIN): Boolean = order(task).any { usable(it) }

    /** Whether a cloud key exists. Background work needs this: AICore refuses background calls. */
    val cloudConfigured: Boolean get() = cloud.configured

    suspend fun generate(task: AiTask, request: TextRequest): TextResult {
        if (local.status.value == OnDeviceGemini.Status.UNKNOWN) local.refresh()

        val candidates = order(task).filter { usable(it) }
        if (candidates.isEmpty()) throw AiException(missingMessage())

        var last: Throwable? = null
        for (model in candidates) {
            try {
                return TextResult(model.generate(request), model.modelId, model.onDevice)
            } catch (e: Exception) {
                // A Nano miss (bad JSON, refused output) is expected now and
                // then; the next model in line gets the same request.
                Log.i(TAG, "$task on ${model.modelId} failed: ${e.message}")
                last = e
            }
        }
        throw (last as? AiException) ?: AiException(last?.message ?: "생성에 실패했습니다.", last)
    }

    private fun order(task: AiTask): List<TextModel> = when (settings.engine) {
        AiEngine.CLOUD -> listOf(cloud)
        AiEngine.ON_DEVICE -> listOf(local)
        AiEngine.AUTO -> if (task.onDeviceFirst) listOf(local, cloud) else listOf(cloud, local)
    }

    private fun usable(model: TextModel): Boolean = when (model) {
        is CloudGemini -> model.configured
        // UNKNOWN counts as usable so the first call gets to find out.
        else -> local.status.value.let {
            it == OnDeviceGemini.Status.AVAILABLE || it == OnDeviceGemini.Status.UNKNOWN
        }
    }

    private fun missingMessage(): String = when (settings.engine) {
        AiEngine.CLOUD -> "Gemini API 키가 필요합니다. 설정에서 넣으세요."
        AiEngine.ON_DEVICE ->
            "기기 안 모델(Gemini Nano)을 쓸 수 없습니다: ${local.status.value.label}. " +
                "설정에서 상태를 확인하세요."
        AiEngine.AUTO ->
            "기기 안 모델(Gemini Nano)을 쓸 수 없고 Gemini API 키도 없습니다. 설정에서 둘 중 하나를 준비하세요."
    }

    private companion object {
        const val TAG = "AiRouter"
    }
}
