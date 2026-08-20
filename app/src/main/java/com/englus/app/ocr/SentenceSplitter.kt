package com.englus.app.ocr

/**
 * Splits recognised text into study-sized sentences.
 *
 * OCR output is not clean prose: it arrives with hard line breaks from the page
 * layout, hyphenated words split across lines, and headings with no terminal
 * punctuation. Splitting naively on "." produces fragments that are useless to
 * translate and impossible to review.
 */
object SentenceSplitter {

    /** Beyond this a "sentence" is really a paragraph and stops being reviewable. */
    private const val MAX_CHARS = 300
    private const val MIN_CHARS = 2

    fun split(raw: String): List<String> {
        val text = normalize(raw)
        if (text.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()

        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)

            if (c == '.' || c == '!' || c == '?') {
                // Consume runs like "?!" and the closing quote or bracket that
                // belongs to the sentence rather than the next one.
                while (i + 1 < text.length && text[i + 1] in TERMINALS) {
                    current.append(text[++i])
                }
                while (i + 1 < text.length && text[i + 1] in CLOSERS) {
                    current.append(text[++i])
                }

                if (endsSentence(text, i, current)) {
                    flush(out, current)
                }
            }
            i++
        }
        flush(out, current)

        // A run-on with no punctuation at all would otherwise become one giant
        // block; break it so at least something is reviewable.
        return out.flatMap { if (it.length > MAX_CHARS) hardWrap(it) else listOf(it) }
    }

    /**
     * Collapses the layout artefacts before any splitting happens: joining
     * hyphenated line breaks first means "inter-\nnational" is one word by the
     * time sentence detection looks at it.
     */
    internal fun normalize(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace(Regex("(\\p{L})-\\n(\\p{L})"), "$1$2")
        .replace(Regex("[ \\t]*\\n[ \\t]*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun endsSentence(text: String, dotIndex: Int, current: StringBuilder): Boolean {
        val next = text.getOrNull(dotIndex + 1) ?: return true
        if (next != ' ') return false

        // The trailing period is part of the token being matched — "dr." is in the
        // abbreviation list, "dr" is not — so only closing quotes are stripped.
        val word = current.toString().trimEnd(*CLOSERS.toCharArray()).substringAfterLast(' ')
        // "Mr." and "U.S." are not sentence ends.
        if (word.lowercase() in ABBREVIATIONS) return false
        if (Regex("^(\\p{L}\\.)+$").matches(word)) return false

        // A lowercase continuation after a period is almost always an
        // abbreviation the list above missed, not a new sentence.
        val after = text.getOrNull(dotIndex + 2) ?: return true
        return !after.isLowerCase()
    }

    private fun flush(out: MutableList<String>, current: StringBuilder) {
        val s = current.toString().trim()
        current.setLength(0)
        if (s.length >= MIN_CHARS && s.any { it.isLetterOrDigit() }) out += s
    }

    private fun hardWrap(long: String): List<String> {
        val words = long.split(" ")
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        words.forEach { w ->
            if (buf.isNotEmpty() && buf.length + w.length + 1 > MAX_CHARS) {
                chunks += buf.toString().trim()
                buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(w)
        }
        if (buf.isNotEmpty()) chunks += buf.toString().trim()
        return chunks
    }

    private const val TERMINALS = ".!?"
    private const val CLOSERS = "\"')]”’"

    private val ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.",
        "vs.", "etc.", "e.g.", "i.e.", "fig.", "no.", "vol.", "approx.",
        "jan.", "feb.", "mar.", "apr.", "jun.", "jul.", "aug.", "sep.", "sept.",
        "oct.", "nov.", "dec.",
    )
}
