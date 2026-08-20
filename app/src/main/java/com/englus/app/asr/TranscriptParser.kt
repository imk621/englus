package com.englus.app.asr

import com.englus.app.data.Sentence
import com.englus.app.data.Word
import org.json.JSONObject

/**
 * Turns the JNI payload into the rows the player reads.
 *
 * The interesting part is token -> word merging. Whisper emits sub-word tokens
 * (" Plan", "et", " Money"), and a karaoke highlight on "et" is noise. A token
 * that begins with a space starts a new word; everything else glues onto the
 * one before it.
 */
object TranscriptParser {

    class ParseException(message: String) : Exception(message)

    fun parse(episodeId: Long, json: String): List<Sentence> {
        val root = JSONObject(json)
        if (!root.optBoolean("ok")) {
            throw ParseException(root.optString("error", "전사 실패"))
        }

        val segments = root.optJSONArray("segments") ?: return emptyList()
        val out = ArrayList<Sentence>(segments.length())

        for (i in 0 until segments.length()) {
            val seg = segments.getJSONObject(i)
            val text = seg.optString("text").trim()
            if (text.isEmpty()) continue

            val tokens = seg.optJSONArray("tokens")
            val words = ArrayList<Word>()
            var buf: StringBuilder? = null
            var start = 0L
            var end = 0L

            for (j in 0 until (tokens?.length() ?: 0)) {
                val t = tokens!!.getJSONObject(j)
                val raw = t.optString("text")
                if (raw.isBlank()) continue
                // t0/t1 rather than tdtw: the span is what a highlight needs, and
                // monotonicity of t0/t1 is what the Phase 0 tests actually verified.
                val t0 = t.optLong("t0")
                val t1 = t.optLong("t1")

                if (buf == null || raw.startsWith(" ")) {
                    buf?.let { words += Word(it.toString().trim(), start, end) }
                    buf = StringBuilder(raw)
                    start = t0
                    end = t1
                } else {
                    buf.append(raw)
                    end = t1
                }
            }
            buf?.let { words += Word(it.toString().trim(), start, end) }

            out += Sentence(
                episodeId = episodeId,
                position = out.size,
                text = text,
                startMs = seg.optLong("t0"),
                endMs = seg.optLong("t1"),
                words = words.filter { it.text.isNotEmpty() },
            )
        }
        return out
    }
}
