package com.englus.app.ui.quality

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.DiffStatus
import com.englus.app.dictation.WordDiff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckResult(
    val diff: DiffResult,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
) {
    /** Word error rate: the standard measure, so the number is comparable to anything published. */
    val wer: Float
        get() = if (diff.totalWords == 0) 0f
        else (substitutions + deletions + insertions).toFloat() / diff.totalWords
}

/**
 * Word-level SRT.
 *
 * There is no ground truth for word timings on the device, so the only honest
 * check is the learner's own ears: load this beside the audio in any player and
 * see whether the cues land on the words. A cue gets a floor duration because a
 * 60 ms word is unreadable as it flashes past.
 */
internal fun buildWordSrt(sentences: List<com.englus.app.data.Sentence>): String {
    val out = StringBuilder()
    var n = 1
    sentences.forEach { sentence ->
        sentence.words.forEach { w ->
            val start = w.startMs
            val end = maxOf(w.endMs, start + 250)
            out.appendLine(n++)
            out.appendLine(srtTime(start) + " --> " + srtTime(end))
            out.appendLine(w.text)
            out.appendLine()
        }
    }
    return out.toString()
}

private fun srtTime(ms: Long): String {
    val v = ms.coerceAtLeast(0)
    val h = v / 3_600_000
    val m = (v % 3_600_000) / 60_000
    val s = (v % 60_000) / 1000
    val milli = v % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
}

data class CheckUi(
    val reference: String = "",
    val transcript: String = "",
    val result: CheckResult? = null,
    val srt: String = "",
)

/**
 * Measures how good the on-device transcript actually is.
 *
 * Judging a transcript by eye is unreliable — it reads fluently even when words
 * are wrong. VOA and BBC publish transcripts for their learning material, so
 * pasting one in turns "it looks about right" into a number that can be compared
 * against a different model or a different episode.
 */
class TranscriptCheckViewModel(app: Application, episodeId: Long) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository

    private val _ui = MutableStateFlow(CheckUi())
    val ui: StateFlow<CheckUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val sentences = repo.observeSentences(episodeId).first()
            _ui.update {
                it.copy(
                    transcript = sentences.joinToString(" ") { s -> s.text.trim() },
                    srt = buildWordSrt(sentences),
                )
            }
        }
    }

    fun setReference(v: String) = _ui.update { it.copy(reference = v, result = null) }

    fun check() {
        val ui = _ui.value
        if (ui.reference.isBlank() || ui.transcript.isBlank()) return
        // Reference is the truth, the app's transcript is the hypothesis — the
        // opposite order would report the inverse error rate.
        val diff = WordDiff.compare(ui.reference, ui.transcript)
        _ui.update {
            it.copy(
                result = CheckResult(
                    diff = diff,
                    substitutions = diff.reference.count { t -> t.status == DiffStatus.WRONG },
                    deletions = diff.reference.count { t -> t.status == DiffStatus.MISSING },
                    insertions = diff.hypothesis.count { t -> t.status == DiffStatus.EXTRA },
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptCheckScreen(vm: TranscriptCheckViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("전사 정확도 확인") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "공개된 대본을 붙여넣으면 이 기기의 전사와 비교해 단어 오류율(WER)을 계산합니다. " +
                    "VOA Learning English와 BBC 6 Minute English는 대본을 공개합니다.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = ui.reference,
                onValueChange = vm::setReference,
                label = { Text("공식 대본") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
            )
            Button(
                onClick = { vm.check() },
                enabled = ui.reference.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("비교하기") }

            if (ui.srt.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("단어 타임스탬프 확인", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "단어 단위 자막(SRT)을 내보냅니다. 오디오와 함께 아무 플레이어에 " +
                                "올려 들리는 단어와 자막이 맞는지 보면, 가라오케와 구간 반복이 " +
                                "제대로 설 수 있는지 판단할 수 있습니다.",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, ui.srt)
                                }
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "SRT 내보내기")
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("단어 SRT 내보내기") }
                    }
                }
            }

            ui.result?.let { r ->
                val pct = (r.wer * 100)
                val color = when {
                    pct < 10 -> Color(0xFF1B5E20)
                    pct < 20 -> Color(0xFF7D5260)
                    else -> Color(0xFFB3261E)
                }
                Card(colors = CardDefaults.cardColors(containerColor = color)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "WER %.1f%%".format(pct),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            verdict(pct),
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 12.sp,
                        )
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("대본 단어 수", Modifier.weight(1f), fontSize = 13.sp)
                            Text("${r.diff.totalWords}", fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text("치환", Modifier.weight(1f), fontSize = 13.sp)
                            Text("${r.substitutions}", fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text("누락", Modifier.weight(1f), fontSize = 13.sp)
                            Text("${r.deletions}", fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text("삽입", Modifier.weight(1f), fontSize = 13.sp)
                            Text("${r.insertions}", fontSize = 13.sp)
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("대본 대비 차이", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(annotate(r.diff), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun verdict(pct: Float): String = when {
    pct < 10 -> "학습용으로 충분합니다. 받아쓰기 채점의 정답으로 믿고 써도 됩니다."
    pct < 20 -> "대체로 쓸 만하지만 틀린 단어가 섞여 있습니다. 더 큰 모델을 시험해볼 값이 있습니다."
    else -> "전사가 틀린 것을 가르칠 수준입니다. 모델을 키우거나 더 또렷한 오디오를 쓰세요."
}

@Composable
private fun annotate(diff: DiffResult) = buildAnnotatedString {
    val errorColor = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    diff.reference.forEachIndexed { i, t ->
        if (i > 0) append(" ")
        when (t.status) {
            DiffStatus.MATCH -> append(t.text)
            DiffStatus.WRONG -> withStyle(
                SpanStyle(color = errorColor, fontWeight = FontWeight.Bold)
            ) { append(t.text) }
            DiffStatus.MISSING -> withStyle(
                SpanStyle(color = errorColor, textDecoration = TextDecoration.Underline)
            ) { append(t.text) }
            DiffStatus.EXTRA -> withStyle(SpanStyle(color = muted)) { append(t.text) }
        }
    }
}
