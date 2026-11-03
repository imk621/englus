package com.englus.app.ui.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.size

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    vm: LiveViewModel,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("실시간 회화") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let { message ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(message, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = vm::dismissError) { Text("확인", fontSize = 12.sp) }
                        }
                    }
                }
            }

            if (!ui.configured) {
                item { NeedsKeyCard(onOpenSettings) }
                return@LazyColumn
            }

            when (ui.phase) {
                LivePhase.PICKING -> {
                    item { CostCard(ui, vm) }
                    item { TopicCard(ui, vm) }
                    item {
                        Button(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) vm.start()
                                else askMic.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            enabled = ui.remainingSec > 0 && ui.topic != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    ui.dailyMinutes == 0 -> "먼저 하루 한도를 정하세요"
                                    ui.remainingSec > 0 -> "대화 시작"
                                    else -> "오늘 시간 소진"
                                }
                            )
                        }
                    }
                    item { HistorySection(vm) }
                }

                // A cancel button, because connecting is the one state that can
                // stall on something outside the app - a wrong key, a dead
                // network - and a screen with no way out is a screen you force
                // quit.
                LivePhase.CONNECTING -> item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("연결 중", fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = vm::cancelConnecting) { Text("취소") }
                    }
                }

                LivePhase.TALKING -> {
                    item { LiveHeader(ui, vm) }
                    items(ui.turns.size) { i -> TurnBubble(ui.turns[i].fromUser, ui.turns[i].text) }
                    if (ui.partialUser.isNotBlank()) {
                        item { TurnBubble(true, ui.partialUser, faded = true) }
                    }
                    if (ui.partialModel.isNotBlank()) {
                        item { TurnBubble(false, ui.partialModel, faded = true) }
                    }
                }

                LivePhase.SAVED -> {
                    item { EndedCard(ui, vm) }
                    items(ui.turns.size) { i -> TurnBubble(ui.turns[i].fromUser, ui.turns[i].text) }
                }
            }
        }
    }
}

@Composable
private fun NeedsKeyCard(onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Gemini API 키가 필요합니다", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "실시간 회화는 이 앱에서 유일하게 사용한 만큼 요금이 나가는 기능입니다. " +
                    "다른 기능은 모두 기기 안에서 돌거나 짧은 텍스트 요청뿐입니다.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenSettings) { Text("설정 열기") }
        }
    }
}

/**
 * The budget, before the button that spends it.
 *
 * Put first and not in a settings screen, because a limit the learner never
 * sees is one they will only find out about from a bill.
 */
@Composable
private fun CostCard(ui: LiveUi, vm: LiveViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (ui.dailyMinutes == 0) "꺼져 있습니다"
                else "오늘 남은 시간 ${ui.remainingSec / 60}분 ${ui.remainingSec % 60}초",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (ui.dailyMinutes == 0) {
                    "이 기능은 기본으로 꺼져 있습니다. 앱에서 유일하게 실제로 요금이 " +
                        "나가는 기능이라, 쓰겠다고 정하기 전에는 켜지지 않는 편이 맞습니다. " +
                        "아래에서 하루 한도를 고르면 켜집니다."
                } else {
                    "음성은 오가는 양만큼 과금됩니다. 말하는 동안 계속 흐르기 때문에 " +
                        "끊는 것을 잊는 것이 가장 흔한 사고입니다. 그래서 하루 한도를 앱 안에서 " +
                        "강제하고, 1초 단위로 미리 차감합니다."
                },
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("하루 한도", fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 5, 10, 20, 30).forEach { minutes ->
                    FilterChip(
                        selected = ui.dailyMinutes == minutes,
                        onClick = { vm.setDailyMinutes(minutes) },
                        label = { Text(if (minutes == 0) "끔" else "${minutes}분", fontSize = 11.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicCard(ui: LiveUi, vm: LiveViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("무엇에 대해 이야기할까요", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "듣던 자료가 위에 나옵니다. 아침에 들은 에피소드로 이야기하면 " +
                    "방금 만난 단어를 그대로 다시 쓰게 됩니다. 아무 주제나 잡으면 " +
                    "아무 영어나 나옵니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ui.topics.forEach { topic ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = ui.topic == topic,
                        onClick = { vm.setTopic(topic) },
                        label = { Text(topic.label, fontSize = 12.sp, maxLines = 2) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "이어폰을 쓰는 편이 좋습니다. 스피커로 하면 상대의 목소리가 마이크로 " +
                    "다시 들어가 서로 말을 끊습니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveHeader(ui: LiveUi, vm: LiveViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "%d:%02d".format(ui.elapsedSec / 60, ui.elapsedSec % 60),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                    Text("남은 시간 ${ui.remainingSec / 60}분 ${ui.remainingSec % 60}초", fontSize = 11.sp)
                }
                Button(
                    onClick = vm::stop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("끝내기") }
            }
            Spacer(Modifier.height(10.dp))
            // A level meter, so silence is distinguishable from a dead microphone.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ui.micLevel.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun TurnBubble(fromUser: Boolean, text: String, faded: Boolean = false) {
    val container = if (fromUser) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.width(36.dp))
        Card(
            Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = if (faded) container.copy(alpha = 0.5f) else container
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (fromUser) "나" else "상대",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(text, fontSize = 14.sp)
            }
        }
        if (!fromUser) Spacer(Modifier.width(36.dp))
    }
}

@Composable
private fun EndedCard(ui: LiveUi, vm: LiveViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "%d분 %d초 대화했습니다".format(ui.elapsedSec / 60, ui.elapsedSec % 60),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "대화 중에는 일부러 고쳐주지 않습니다. 관사 하나 때문에 말이 끊기면 " +
                    "다음부터 입을 열지 않게 됩니다. 첨삭은 지금, 기록을 보고 합니다. " +
                    "이 요청은 음성이 아니라 텍스트라 요금이 거의 들지 않습니다.",
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::review,
                    enabled = !ui.reviewing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (ui.reviewing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("첨삭 중")
                    } else {
                        Text("첨삭 받기")
                    }
                }
                OutlinedButton(onClick = vm::reset) { Text("새 대화") }
            }
        }
    }
}

@Composable
private fun HistorySection(vm: LiveViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    if (history.isEmpty()) return

    Column {
        Text("지난 대화", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        history.forEach { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.topic, fontSize = 13.sp, maxLines = 1)
                    Text(
                        "%d분 %d초".format(c.durationSec / 60, c.durationSec % 60) +
                            if (c.mistakeCount > 0) " · 고칠 곳 ${c.mistakeCount}군데" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (c.feedback.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(c.feedback, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
