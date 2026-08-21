package com.englus.app.sound

/**
 * The ways spoken English stops matching its spelling.
 *
 * A learner diagnosed with SOUND failures is not missing vocabulary — they are
 * missing the transformations below. Grouping their own failed sentences by
 * which transformation appears in them turns "you can't hear it" into a drill
 * with a specific target.
 *
 * Detection is rule-based on the transcript text. It is a heuristic, not a
 * phonetic analysis: the goal is to gather sentences that plausibly contain the
 * pattern so they can be listened to again, not to prove they do.
 */
enum class SoundPattern(val label: String, val advice: String) {
    CONTRACTION(
        "축약형",
        "don't, I'll, we've 처럼 줄어든 형태입니다. 글자로는 알아도 소리로는 한 덩어리로 지나갑니다.",
    ),
    GONNA(
        "구어 축약",
        "going to → gonna, want to → wanna, a lot of → alotta. 철자와 소리가 가장 크게 어긋나는 구간입니다.",
    ),
    WEAK_FORM(
        "약형 기능어",
        "to, of, for, and, can, are 같은 기능어는 강세를 잃고 거의 사라집니다. 안 들리는 게 정상이고, 없는 셈 치고 문장 구조로 채워 넣어야 합니다.",
    ),
    LINKING(
        "연음",
        "자음으로 끝난 단어와 모음으로 시작하는 단어가 붙습니다. pick it up → 피키럽.",
    ),
    FLAP_T(
        "플랩 T",
        "모음 사이의 t가 ㄹ에 가깝게 바뀝니다. water → 워러, get it → 게릿.",
    ),
    H_DROP(
        "h 탈락",
        "him, her, his 의 h가 앞 단어에 먹힙니다. tell him → 텔림.",
    );
}

data class PatternHit(val pattern: SoundPattern, val spans: List<String>)

object SoundPatternDetector {

    fun detect(sentence: String): List<PatternHit> {
        val text = " ${sentence.lowercase()} "
        return SoundPattern.entries.mapNotNull { pattern ->
            val spans = spansFor(pattern, text)
            if (spans.isEmpty()) null else PatternHit(pattern, spans.distinct().take(4))
        }
    }

    fun patterns(sentence: String): Set<SoundPattern> = detect(sentence).map { it.pattern }.toSet()

    private fun spansFor(pattern: SoundPattern, text: String): List<String> = when (pattern) {
        SoundPattern.CONTRACTION -> CONTRACTION.findAll(text).map { it.value.trim() }.toList()
        SoundPattern.GONNA -> GONNA.findAll(text).map { it.value.trim() }.toList()
        SoundPattern.FLAP_T -> FLAP_T.findAll(text).map { it.value.trim() }.toList()
        SoundPattern.H_DROP -> H_DROP.findAll(text).map { it.value.trim() }.toList()
        SoundPattern.WEAK_FORM ->
            WEAK_WORDS.filter { text.contains(" $it ") }.map { it }
        SoundPattern.LINKING -> linking(text)
    }

    /**
     * Consonant-final word followed by a vowel-initial one.
     *
     * Deliberately ignores whether the final letter is a *silent* consonant —
     * spelling is all this has to go on, and over-collecting a few sentences is
     * far cheaper than missing the pattern the learner actually needs.
     */
    private fun linking(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        for (i in 0 until words.lastIndex) {
            val a = words[i].trim { !it.isLetter() && it != '\'' }
            val b = words[i + 1].trim { !it.isLetter() && it != '\'' }
            if (a.isEmpty() || b.isEmpty()) continue
            val endsConsonant = a.last().isLetter() && a.last() !in VOWELS
            val startsVowel = b.first() in VOWELS
            if (endsConsonant && startsVowel) out += "$a $b"
        }
        return out
    }

    private const val VOWELS = "aeiou"

    private val CONTRACTION = Regex("\\b[a-z]+n't\\b|\\b[a-z]+'(ll|ve|re|d|s|m)\\b")

    private val GONNA = Regex(
        "\\b(going to|want to|wants to|got to|have to|has to|ought to|" +
            "kind of|sort of|out of|lot of|because of|used to|" +
            "gonna|wanna|gotta|hafta|lemme|gimme)\\b"
    )

    /** Vowel + t + vowel inside a word, or a word-final t running into a vowel. */
    private val FLAP_T = Regex("\\b[a-z]*[aeiou]t[aeiouy][a-z]*\\b|\\b[a-z]*[aeiou]t\\s+[aeiou][a-z]*\\b")

    private val H_DROP = Regex("\\b[a-z]+\\s+(him|her|his|he|has|have)\\b")

    /**
     * The function words that actually vanish at speed. Content words reduce too,
     * but a learner cannot act on that; these can be predicted from grammar.
     */
    private val WEAK_WORDS = listOf(
        "to", "of", "for", "and", "or", "as", "at", "than", "that", "from",
        "can", "was", "were", "are", "am", "is", "been", "be",
        "have", "has", "had", "would", "could", "should", "will", "must",
        "you", "your", "them", "us", "our", "a", "an", "the", "some", "just",
    )
}
