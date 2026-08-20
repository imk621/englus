package com.englus.asr

/** Thin 1:1 binding over `whisper_jni.cpp`. No logic here on purpose. */
object WhisperNative {

    init {
        System.loadLibrary("englus_whisper")
    }

    /** Values of `enum whisper_alignment_heads_preset` in whisper.h. */
    object Aheads {
        const val NONE = 0
        const val N_TOP_MOST = 1
        const val TINY_EN = 3
        const val TINY = 4
        const val BASE_EN = 5
        const val BASE = 6
        const val SMALL_EN = 7
        const val SMALL = 8
        const val MEDIUM_EN = 9
        const val MEDIUM = 10
        const val LARGE_V3_TURBO = 14

        /**
         * DTW needs the alignment heads that belong to the loaded model. Guessing
         * wrong does not fail — it yields plausible-looking but wrong `t_dtw`
         * values, which is the worst outcome for a timestamp spike. So derive it
         * from the model filename and surface the choice in the UI.
         */
        fun forModelFile(name: String): Int {
            val n = name.lowercase()
            val en = n.contains(".en")
            return when {
                n.contains("large-v3-turbo") -> LARGE_V3_TURBO
                n.contains("medium") -> if (en) MEDIUM_EN else MEDIUM
                n.contains("small") -> if (en) SMALL_EN else SMALL
                n.contains("base") -> if (en) BASE_EN else BASE
                n.contains("tiny") -> if (en) TINY_EN else TINY
                else -> N_TOP_MOST
            }
        }

        fun label(preset: Int): String = when (preset) {
            NONE -> "NONE"; N_TOP_MOST -> "N_TOP_MOST"
            TINY_EN -> "TINY_EN"; TINY -> "TINY"
            BASE_EN -> "BASE_EN"; BASE -> "BASE"
            SMALL_EN -> "SMALL_EN"; SMALL -> "SMALL"
            MEDIUM_EN -> "MEDIUM_EN"; MEDIUM -> "MEDIUM"
            LARGE_V3_TURBO -> "LARGE_V3_TURBO"
            else -> "?"
        }
    }

    external fun systemInfo(): String

    /** @return native context pointer, or 0 on failure. */
    external fun initContext(modelPath: String, useDtw: Boolean, aheadsPreset: Int): Long

    external fun freeContext(ptr: Long)

    /** @return JSON: `{ok, segments:[{t0,t1,text,tokens:[{text,t0,t1,tdtw,p}]}], timings:{}}` */
    /** [audio] may be longer than [nSamples]; only the first [nSamples] are read. */
    external fun transcribe(
        ptr: Long,
        audio: FloatArray,
        nSamples: Int,
        nThreads: Int,
        language: String,
        tokenTimestamps: Boolean,
        useDtw: Boolean,
        maxLen: Int,
        splitOnWord: Boolean,
    ): String
}
