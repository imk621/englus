package com.englus.app.vocab

import android.content.Context

/**
 * The bundled frequency list, loaded once.
 *
 * 10,000 words is the point of diminishing returns: it covers roughly 98% of
 * running text, so what falls outside is genuinely rare or a proper noun, and
 * either way is not a vocabulary item to schedule. The file is 73 KB and the
 * map is built once per process.
 *
 * Derived from hermitdave/FrequencyWords (MIT); see the NOTICE beside the asset.
 */
class FrequencyList private constructor(private val ranks: Map<String, Int>) : WordRanks {

    override fun rank(word: String): Int? = ranks[word]

    override val size: Int get() = ranks.size

    companion object {
        private const val ASSET = "en_freq_10k.txt"

        @Volatile private var instance: FrequencyList? = null

        fun get(context: Context): FrequencyList = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        private fun load(context: Context): FrequencyList {
            val map = HashMap<String, Int>(16_384)
            runCatching {
                context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                    var rank = 0
                    lines.forEach { line ->
                        val w = line.trim()
                        if (w.isNotEmpty()) map[w] = ++rank
                    }
                }
            }
            return FrequencyList(map)
        }
    }
}
