package com.englus.app.ui.level

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.level.LevelResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(vm: LevelViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("수준 측정") },
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
            val result = ui.result
            when {
                ui.loading -> Text("문제를 고르는 중", fontSize = 13.sp)
                result != null -> ResultView(result, ui.previousSize, vm)
                else -> Question(ui, vm)
            }
        }
    }
}

@Composable
private fun Question(ui: LevelUi, vm: LevelViewModel) {
    val item = ui.current ?: return

    LinearProgressIndicator(
        progress = { if (ui.total == 0) 0f else ui.index.toFloat() / ui.total },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${ui.index + 1} / ${ui.total}",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(20.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.word, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.answer(false) }, modifier = Modifier.weight(1f)) {
            Text("모름")
        }
        Button(onClick = { vm.answer(true) }, modifier = Modifier.weight(1f)) {
            Text("뜻을 안다")
        }
    }

    Spacer(Modifier.height(16.dp))
    // Stated up front: the test only works on honest answers, and people answer
    // honestly when they know why it matters.
    Text(
        "뜻이 바로 떠오르면 '뜻을 안다'입니다. 본 적 있는 것 같다는 느낌만으로는 " +
            "아닙니다. 이 중에는 영어에 없는 단어도 섞여 있어서, 그런 것에 " +
            "'안다'를 누르면 나머지 답도 그만큼 깎입니다.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ResultView(result: LevelResult, previousSize: Int?, vm: LevelViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("어휘 ${result.vocabularySize}개", fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Spacer(Modifier.height(2.dp))
            Text("${result.cefr.label} · ${result.cefr.blurb}", fontSize = 13.sp)
            previousSize?.let {
                Spacer(Modifier.height(6.dp))
                val delta = result.vocabularySize - it
                Text(
                    when {
                        delta > 0 -> "지난번보다 ${delta}개 늘었습니다 ($it → ${result.vocabularySize})"
                        delta < 0 -> "지난번보다 ${-delta}개 줄었습니다 ($it → ${result.vocabularySize})"
                        else -> "지난번과 같습니다"
                    },
                    fontSize = 12.sp,
                )
            }
        }
    }

    if (result.unreliable) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("이 결과는 믿을 수 없습니다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "영어에 없는 단어의 ${(result.falseAlarmRate * 100).toInt()}%에 '안다'를 " +
                        "눌렀습니다. 뜻이 실제로 떠오르는 것만 고르고 다시 해보세요.",
                    fontSize = 12.sp,
                )
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("빈도 구간별", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            val labels = listOf("1-1000", "1001-2000", "2001-3000", "3001-5000", "5001-10000")
            result.bands.forEach { band ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(labels[band.band], Modifier.width(96.dp), fontSize = 12.sp)
                    Text("${band.claimed}/${band.asked}", Modifier.width(48.dp), fontSize = 12.sp)
                    Text(
                        "${(band.corrected * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "가운데는 '안다'를 누른 개수, 오른쪽은 과잉 응답을 뺀 값입니다. " +
                    "영어에 없는 단어에 ${(result.falseAlarmRate * 100).toInt()}% 응답했고, " +
                    "그만큼 모든 구간에서 깎았습니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            // Two different questions, and conflating them would overstate what
            // this five-minute test can tell anyone.
            Text(
                "이 값은 '뜻을 아는 단어 수'의 추정치입니다. 듣고 받아 적을 수 있는지는 " +
                    "또 다른 문제이고, 그건 받아쓰기가 쌓는 어휘 프로필이 따로 답합니다. " +
                    "여기는 첫날부터 자료를 고르기 위한 출발점입니다.",
                fontSize = 12.sp,
            )
        }
    }

    OutlinedButton(onClick = vm::start, modifier = Modifier.fillMaxWidth()) {
        Text("다시 측정")
    }
}
