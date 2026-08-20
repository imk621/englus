package com.englus.app.ui.dictation

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.data.Diagnosis
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.DiffStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(vm: DictationViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("받아쓰기  ${ui.index + 1} / ${ui.total}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
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
            if (ui.total > 0) {
                LinearProgressIndicator(
                    progress = { (ui.index + 1).toFloat() / ui.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (ui.finished) {
                Text("이 에피소드를 끝냈습니다.", fontWeight = FontWeight.Bold)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                return@Column
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { vm.playCurrent() }) {
                    Icon(Icons.Default.Replay, null)
                    Spacer(Modifier.height(0.dp))
                    Text("  다시 듣기")
                }
                if (ui.slow) {
                    Text("0.7배속", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            when (ui.phase) {
                Phase.TYPING -> {
                    OutlinedTextField(
                        value = ui.input,
                        onValueChange = vm::setInput,
                        label = { Text("들리는 대로 입력") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Button(
                        onClick = { vm.check() },
                        enabled = ui.input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("확인") }
                    TextButton(onClick = { vm.skip() }, modifier = Modifier.fillMaxWidth()) {
                        Text("전혀 모르겠음 — 넘어가기", fontSize = 12.sp)
                    }
                }

                Phase.WRONG -> {
                    // The text stays hidden here on purpose: showing it now would
                    // make the slow replay meaningless and destroy the one signal
                    // that separates "too fast" from "cannot hear it".
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("아직 맞지 않습니다", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "정답을 보기 전에 느리게 한 번 더 들어보세요. " +
                                    "느리게 들으면 들린다면 문제는 소리가 아니라 속도입니다.",
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Button(onClick = { vm.retrySlowly() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.SlowMotionVideo, null)
                        Text("  0.7배속으로 다시 듣기")
                    }
                    OutlinedButton(onClick = { vm.revealAnswer() }, modifier = Modifier.fillMaxWidth()) {
                        Text("정답 보기")
                    }
                }

                Phase.ASK_MEANING -> {
                    ui.diff?.let { DiffCard(it) }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("이 문장, 읽으면 뜻을 아시겠습니까?", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "안다면 원인은 어휘가 아니라 소리입니다. 연음·축약·약형 훈련이 필요합니다.",
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.answerMeaning(true) },
                            modifier = Modifier.weight(1f),
                        ) { Text("압니다") }
                        OutlinedButton(
                            onClick = { vm.answerMeaning(false) },
                            modifier = Modifier.weight(1f),
                        ) { Text("모릅니다") }
                    }
                }

                Phase.DONE -> {
                    ui.diagnosis?.let { DiagnosisCard(it, ui.diff) }
                    ui.diff?.let { DiffCard(it) }
                    Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) {
                        Text("다음 문장")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosisCard(diagnosis: Diagnosis, diff: DiffResult?) {
    val (label, explain, color) = when (diagnosis) {
        Diagnosis.CORRECT -> Triple("정답", "그대로 들렸습니다.", Color(0xFF1B5E20))
        Diagnosis.SPEED -> Triple(
            "처리 속도",
            "느리게 들으니 맞혔습니다. 아는 소리인데 따라가지 못한 것이라, 배속을 조금씩 올리며 반복하면 좁혀집니다.",
            Color(0xFF0D47A1),
        )
        Diagnosis.SOUND -> Triple(
            "소리 인지 실패",
            "읽으면 아는 문장을 못 들었습니다. 어휘를 더 외워도 해결되지 않습니다 — 이 구간을 반복해서 연음·약형에 귀를 맞추세요.",
            Color(0xFF6A1B9A),
        )
        Diagnosis.VOCAB -> Triple(
            "어휘 부족",
            "모르는 단어라 들릴 수 없었습니다. 이 문장이 어휘 카드로 저장됐습니다.",
            Color(0xFF8D4E00),
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                diff?.let {
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "   정확도 ${(it.accuracy * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(explain, color = Color.White.copy(alpha = 0.95f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun DiffCard(diff: DiffResult) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text("정답", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(annotate(diff, reference = true), fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            Text("입력", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(annotate(diff, reference = false), fontSize = 15.sp)
        }
    }
}

@Composable
private fun annotate(diff: DiffResult, reference: Boolean) = buildAnnotatedString {
    val tokens = if (reference) diff.reference else diff.hypothesis
    val errorColor = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    tokens.forEachIndexed { i, t ->
        if (i > 0) append(" ")
        when (t.status) {
            DiffStatus.MATCH -> append(t.text)
            DiffStatus.WRONG -> withStyle(
                SpanStyle(color = errorColor, fontWeight = FontWeight.Bold)
            ) { append(t.text) }
            // Underlined rather than struck through: this word was in the audio
            // and simply never made it into the answer.
            DiffStatus.MISSING -> withStyle(
                SpanStyle(color = errorColor, textDecoration = TextDecoration.Underline)
            ) { append(t.text) }
            DiffStatus.EXTRA -> withStyle(
                SpanStyle(color = muted, textDecoration = TextDecoration.LineThrough)
            ) { append(t.text) }
        }
    }
}
