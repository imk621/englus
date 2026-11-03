package com.englus.app.ui.daily

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
fun DailySpeakScreen(vm: DailySpeakViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("오늘의 문장 말하기") },
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
            val sentence = ui.sentence ?: return@Column

            if (ui.modelMissing) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "whisper 모델이 필요합니다. 듣기 탭에서 먼저 가져오세요.",
                        Modifier.padding(14.dp),
                        fontSize = 13.sp,
                    )
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("영어로 말해보세요", fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(sentence.korean, fontSize = 19.sp)
                    if (sentence.words.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "이 단어를 쓰세요: " + sentence.words.joinToString(", "),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (!granted) {
                Button(
                    onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("마이크 권한 허용") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilledIconButton(
                        onClick = { if (ui.recording) vm.stopAndScore() else vm.startRecording() },
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
                        else -> "버튼을 누르고 영어로 말하세요"
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

            if (!ui.revealed) {
                // Giving up is allowed but costs the attempt: seeing the answer
                // first would turn this into a reading exercise.
                OutlinedButton(onClick = vm::reveal, modifier = Modifier.fillMaxWidth()) {
                    Text("모르겠습니다 · 답 보기", fontSize = 13.sp)
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "이렇게 말합니다",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(sentence.english, fontSize = 17.sp)
                        if (sentence.note.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(sentence.note, fontSize = 12.sp)
                        }
                    }
                }
            }

            ui.diff?.let { diff ->
                ScoreCard(diff, ui.recognized.orEmpty())
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("끝내기") }
            }
        }
    }
}

@Composable
private fun ScoreCard(diff: DiffResult, recognized: String) {
    val accuracy = (diff.accuracy * 100).toInt()
    val color = when {
        accuracy >= 90 -> Color(0xFF1B5E20)
        accuracy >= 70 -> Color(0xFF7D5260)
        else -> Color(0xFFB3261E)
    }

    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "모범답안과 $accuracy% 일치",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            // Saying it differently is not failing, and the number cannot tell
            // the difference between another correct sentence and a wrong one.
            Text(
                "다르게 말했어도 틀린 것은 아닙니다. 뜻이 통하면 됩니다 - " +
                    "아래에서 무엇으로 들렸는지 확인하세요.",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "모범답안 대비",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(annotate(diff), fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "내 발음으로 들린 것",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(recognized.ifBlank { "(아무것도 인식되지 않았습니다)" }, fontSize = 14.sp)
        }
    }
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
