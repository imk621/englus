package com.englus.spike

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { SpikeScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpikeScreen(vm: SpikeViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.setAudio(it)
        }
    }

    // "*/*" rather than a .bin mime type: Android has no registered type for
    // ggml weights, so a narrower filter hides the file in the picker.
    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importModel(it) } }

    Scaffold(topBar = { TopAppBar(title = { Text("englus · ASR 스파이크") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SetupCard(
                    s = s,
                    vm = vm,
                    onPickAudio = { pickAudio.launch(arrayOf("audio/*")) },
                    onPickModel = { pickModel.launch(arrayOf("*/*")) },
                )
            }

            if (s.running) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(s.stage, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            s.error?.let { err ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Text(err, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            s.result?.let { r ->
                item { VerdictCard(r) }
                item { MetricsCard(r, s, vm) }
                item {
                    Text(
                        "세그먼트 ${r.segments.size}개 · 토큰 ${r.tokenCount}개",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(r.segments) { seg -> SegmentCard(seg) }
            }

            item {
                Text(
                    s.systemInfo,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupCard(
    s: UiState,
    vm: SpikeViewModel,
    onPickAudio: () -> Unit,
    onPickModel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text("1. 모델", style = MaterialTheme.typography.titleSmall)
            if (s.models.isEmpty()) {
                Text(
                    "모델이 없습니다. ggml-base.en.bin 등을 내려받은 뒤 " +
                        "아래 '모델 파일 가져오기'로 불러오세요.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.models.forEach { f ->
                        FilterChip(
                            selected = s.selectedModel == f,
                            onClick = { vm.selectModel(f) },
                            label = { Text(f.name.removePrefix("ggml-").removeSuffix(".bin"), fontSize = 12.sp) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                Text("모델 파일 가져오기 (.bin)", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { vm.refreshModels() }) { Text("모델 목록 새로고침") }

            Text("2. 오디오", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onPickAudio, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.audioName.isEmpty()) "오디오 파일 선택" else s.audioName, fontSize = 13.sp)
            }

            Text("3. 설정", style = MaterialTheme.typography.titleSmall)
            Text("스레드: ${s.threads} / ${s.maxThreads} 코어", fontSize = 13.sp)
            Slider(
                value = s.threads.toFloat(),
                onValueChange = { vm.setThreads(it.toInt().coerceAtLeast(1)) },
                valueRange = 1f..s.maxThreads.toFloat(),
                steps = (s.maxThreads - 2).coerceAtLeast(0),
            )
            ToggleRow("DTW 단어 타임스탬프", s.useDtw) { vm.setDtw(it) }
            ToggleRow("단어 단위로 분절 (split_on_word)", s.splitOnWord) { vm.setSplitOnWord(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("세그먼트 최대 길이: ${if (s.maxLen == 0) "제한 없음" else "${s.maxLen}자"}", fontSize = 13.sp)
            }
            Slider(
                value = s.maxLen.toFloat(),
                onValueChange = { vm.setMaxLen(it.toInt()) },
                valueRange = 0f..60f,
            )

            Button(
                onClick = { vm.run() },
                enabled = !s.running && s.selectedModel != null && s.audioUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (s.running) "실행 중…" else "전사 실행") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VerdictCard(r: RunResult) {
    val pass = r.meetsTarget
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pass) Color(0xFF1B5E20) else Color(0xFF7F0000)
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (pass) "PASS · 실시간 대비 %.1f배".format(r.speedX)
                else "FAIL · 실시간 대비 %.1f배".format(r.speedX),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "10분 오디오 환산 %.0f초 (목표 60초 이내)".format(r.projected10MinSec),
                color = Color.White,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun MetricsCard(r: RunResult, s: UiState, vm: SpikeViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Metric("모델", "${r.modelName}  (aheads ${r.aheadsLabel})")
            Metric("오디오", "%.1f초  ·  원본 %d Hz / %dch".format(r.audioSec, r.sourceSampleRate, r.sourceChannels))
            Metric("디코딩", "${r.decodeMs} ms")
            Metric("모델 로딩", "${r.modelLoadMs} ms")
            Metric("전사", "${r.transcribeMs} ms  (${r.threads} threads)")
            Metric("RTF", "%.3f".format(r.rtf))
            Metric("배터리", "${r.batteryStartPct}% → ${r.batteryEndPct}%")
            Metric("온도", "%.1f°C → %.1f°C".format(r.tempStartC, r.tempEndC))
            Metric("native", r.nativeTimings)
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.saveReport() }, modifier = Modifier.fillMaxWidth()) {
                Text("리포트 저장 (JSON + TXT)")
            }
            s.savedPath?.let {
                Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SegmentCard(seg: Segment) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "[%s → %s]".format(fmt(seg.t0), fmt(seg.t1)),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(seg.text.trim(), fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "토큰 접기" else "토큰 타임스탬프 (${seg.tokens.size})", fontSize = 11.sp)
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                seg.tokens.forEach { t ->
                    Text(
                        "%-14s %7s→%-7s dtw=%-7s p=%.2f".format(
                            t.text.trim().take(14), fmt(t.t0), fmt(t.t1),
                            t.tdtw?.let { fmt(it) } ?: "-", t.p
                        ),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun fmt(ms: Long): String = "%d.%02d".format(ms / 1000, (ms % 1000) / 10)
