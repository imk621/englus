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
class FrequencyList private constructor(
    private val ranks: Map<String, Int>,
    private val byRank: List<String>,
) : WordRanks {

    override fun rank(word: String): Int? = ranks[word]

    override val size: Int get() = ranks.size

    /** Ranks are 1-based; the list is not. */
    override fun wordAt(rank: Int): String? = byRank.getOrNull(rank - 1)

    /** Non-words for the level test, verified at build time; see the NOTICE. */
    fun nonWords(context: Context): List<String> = runCatching {
        context.applicationContext.assets.open(NON_WORDS).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val ASSET = "en_freq_10k.txt"
        private const val NON_WORDS = "en_nonwords.txt"

        @Volatile private var instance: FrequencyList? = null

        fun get(context: Context): FrequencyList = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        private fun load(context: Context): FrequencyList {
            val map = HashMap<String, Int>(16_384)
            val ordered = ArrayList<String>(10_000)
            runCatching {
                context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                    var rank = 0
                    lines.forEach { line ->
                        val w = line.trim()
                        if (w.isNotEmpty()) {
                            map[w] = ++rank
                            ordered += w
                        }
                    }
                }
            }
            return FrequencyList(map, ordered)
        }
    }
}
