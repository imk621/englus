package com.englus.app.ui.sound

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPatternListScreen(
    vm: SoundDrillViewModel,
    onBack: () -> Unit,
    onDrill: (String) -> Unit,
) {
    val summaries by vm.summaries.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("소리 패턴 훈련") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "저장된 문장을 소리 변형별로 묶었습니다. 만들어낸 예문이 아니라 " +
                        "실제로 놓친 문장이라, 다시 들으면 그때 무엇이 지나갔는지 보입니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (summaries.isEmpty()) {
                item {
                    Text(
                        "아직 묶을 문장이 없습니다. 받아쓰기에서 틀리거나 북마크한 문장이 여기로 모입니다.",
                        Modifier.padding(top = 20.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(summaries, key = { it.pattern.name }) { s ->
                Card(
                    Modifier.fillMaxWidth().clickable { onDrill(s.pattern.name) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (s.soundDiagnosed > 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.pattern.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${s.total}문장", fontSize = 13.sp)
                        }
                        if (s.soundDiagnosed > 0) {
                            Text(
                                "그중 ${s.soundDiagnosed}개는 '소리' 진단을 받았습니다",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.pattern.advice, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundDrillScreen(vm: SoundDrillViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val item by vm.current.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ui.total > 0) "${vm.pattern?.label ?: "훈련"}  ${ui.index + 1} / ${ui.total}"
                        else vm.pattern?.label ?: "훈련"
                    )
                },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.finished) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "이 패턴을 다 돌았습니다.",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                return@Column
            }

            if (ui.total > 0) {
                LinearProgressIndicator(
                    progress = { (ui.index + 1).toFloat() / ui.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            vm.pattern?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) { Text(it.advice, Modifier.padding(14.dp), fontSize = 12.sp) }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("들어보세요", fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    FilledIconButton(onClick = { vm.play(slow = false) }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Default.Replay, "다시 듣기", Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { vm.play(slow = true) }) {
                        Icon(Icons.Default.SlowMotionVideo, null, Modifier.size(16.dp))
                        Text("  0.65배속")
                    }
                }
            }

            if (!ui.revealed) {
                Button(onClick = { vm.reveal() }, modifier = Modifier.fillMaxWidth()) {
                    Text("문장 보기")
                }
            } else {
                item?.let { saved ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            // The matched spans are highlighted so the eye lands on
                            // the part that vanished, not just on the sentence.
                            Text(highlight(saved.sentence.text, ui.spans), fontSize = 17.sp)
                            saved.sentence.translation?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            if (ui.spans.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "이 구간: ${ui.spans.joinToString(", ")}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) {
                    Text("다음 문장")
                }
            }
        }
    }
}

@Composable
private fun highlight(text: String, spans: List<String>) = buildAnnotatedString {
    val lower = text.lowercase()
    val marks = BooleanArray(text.length)
    spans.forEach { span ->
        var from = 0
        while (true) {
            val i = lower.indexOf(span.lowercase(), from)
            if (i < 0) break
            for (j in i until (i + span.length).coerceAtMost(text.length)) marks[j] = true
            from = i + 1
        }
    }

    val color = MaterialTheme.colorScheme.primary
    var i = 0
    while (i < text.length) {
        val marked = marks[i]
        val start = i
        while (i < text.length && marks[i] == marked) i++
        val chunk = text.substring(start, i)
        if (marked) {
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(chunk) }
        } else {
            append(chunk)
        }
    }
}
