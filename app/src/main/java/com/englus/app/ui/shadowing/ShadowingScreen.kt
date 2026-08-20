package com.englus.app.ui.shadowing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.dictation.DiffResult
import com.englus.app.dictation.DiffStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowingScreen(vm: ShadowingViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sentences by vm.sentences.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("쉐도잉  ${ui.index + 1} / ${ui.total}") },
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
            if (ui.finished) {
                Text("이 에피소드를 끝냈습니다.", fontWeight = FontWeight.Bold)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                return@Column
            }

            if (ui.modelMissing) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "whisper 모델이 필요합니다. 듣기 탭에서 모델을 먼저 가져오세요.",
                        Modifier.padding(14.dp),
                        fontSize = 13.sp,
                    )
                }
            }

            if (ui.total > 0) {
                LinearProgressIndicator(
                    progress = { (ui.index + 1).toFloat() / ui.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val sentence = sentences.getOrNull(ui.index)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("따라 말할 문장", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    // The text is visible here, unlike dictation: shadowing trains
                    // production, not perception, and hiding it would just be a
                    // memory test.
                    Text(sentence?.text.orEmpty(), fontSize = 17.sp)
                }
            }

            OutlinedButton(onClick = { vm.playCurrent() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Replay, null, Modifier.size(18.dp))
                Text("  원본 듣기")
            }

            if (!granted) {
                Button(
                    onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("마이크 권한 허용") }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = {
                            if (ui.recording) vm.stopRecordingAndScore() else vm.startRecording()
                        },
                        enabled = !ui.scoring && !ui.modelMissing,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (ui.recording) Color(0xFFB3261E)
                            else MaterialTheme.colorScheme.primary
                        ),
                    ) {
                        Icon(
                            if (ui.recording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (ui.recording) "정지" else "녹음",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Text(
                    when {
                        ui.scoring -> "채점 중… (기기에서 처리합니다)"
                        ui.recording -> "말한 뒤 정지를 누르세요"
                        else -> "버튼을 누르고 따라 말해보세요"
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ui.error?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) { Text(it, Modifier.padding(12.dp), fontSize = 12.sp) }
            }

            ui.diff?.let { diff ->
                ScoreCard(diff, ui.paceRatio, ui.recognized.orEmpty())
                Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) {
                    Text("다음 문장")
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(diff: DiffResult, paceRatio: Float, recognized: String) {
    val accuracy = (diff.accuracy * 100).toInt()
    val color = when {
        accuracy >= 90 -> Color(0xFF1B5E20)
        accuracy >= 70 -> Color(0xFF7D5260)
        else -> Color(0xFFB3261E)
    }

    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp)) {
            Text("정확도 $accuracy%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            // Word accuracy alone hides rhythm, and rhythm is half of being
            // understood — a perfectly pronounced sentence at half speed still
            // does not sound like English.
            Text(paceLabel(paceRatio), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("문장", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(annotate(diff.reference), fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text("내 발음으로 들린 것", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                recognized.ifBlank { "(아무것도 인식되지 않았습니다)" },
                fontSize = 14.sp,
            )
        }
    }
}

private fun paceLabel(ratio: Float): String = when {
    ratio > 1.35f -> "원본보다 많이 느립니다 (%.1f배). 끊지 말고 이어서 말해보세요.".format(ratio)
    ratio > 1.15f -> "원본보다 조금 느립니다 (%.1f배).".format(ratio)
    ratio < 0.7f -> "원본보다 많이 빠릅니다 (%.1f배). 삼킨 음절이 없는지 보세요.".format(ratio)
    ratio < 0.85f -> "원본보다 조금 빠릅니다 (%.1f배).".format(ratio)
    else -> "속도는 원본과 비슷합니다."
}

@Composable
private fun annotate(tokens: List<com.englus.app.dictation.DiffToken>) = buildAnnotatedString {
    val errorColor = MaterialTheme.colorScheme.error
    tokens.forEachIndexed { i, t ->
        if (i > 0) append(" ")
        when (t.status) {
            DiffStatus.MATCH -> append(t.text)
            DiffStatus.WRONG -> withStyle(
                SpanStyle(color = errorColor, fontWeight = FontWeight.Bold)
            ) { append(t.text) }
            DiffStatus.MISSING -> withStyle(
                SpanStyle(color = errorColor, textDecoration = TextDecoration.Underline)
            ) { append(t.text) }
            DiffStatus.EXTRA -> append(t.text)
        }
    }
}
