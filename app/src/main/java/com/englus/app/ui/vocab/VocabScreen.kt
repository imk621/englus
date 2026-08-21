package com.englus.app.ui.vocab

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.data.VocabBandStat
import com.englus.app.vocab.Band
import com.englus.app.vocab.Cefr
import com.englus.app.vocab.VocabRepository
import androidx.compose.foundation.layout.size

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(vm: VocabViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val unknown by vm.unknownWords.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("어휘 프로필") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
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
            item { SummaryCard(ui) }

            if (ui.met == 0) item { EmptyCard() } else item { BandCard(ui.bands) }

            item { RecommendCard(ui, vm) }

            if (unknown.isNotEmpty()) {
                item {
                    Column {
                        Text("모르는 단어 ${unknown.size}개", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "자주 쓰이는 단어부터 나옵니다. 흔한 단어일수록 곧 다시 만나므로 " +
                                "같은 노력으로 훨씬 많이 돌려받습니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(unknown.size) { i ->
                    val w = unknown[i]
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(w.lemma, fontSize = 15.sp)
                                Text(
                                    bandLabel(w.rank) + " · 틀림 ${w.missed}회",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.markKnown(w.lemma, true) }) {
                                Text("알아요", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(ui: VocabUi) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("확실히 아는 단어 ${ui.known}개", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text("앱이 채점한 ${ui.met}개 중", fontSize = 12.sp)

            ui.estimate?.let {
                Spacer(Modifier.height(10.dp))
                Text("전체 어휘 추정 ${it}개 이상", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                // Saying where a number came from is the difference between a
                // measurement and a decoration.
                Text(
                    "빈도 구간별 정답률로 환산한 값입니다. 어휘력 시험 점수가 아니라 하한선입니다. " +
                        "앱이 채점한 단어는 받아쓰기에서 놓친 문장에 나온 것들이라 어려운 쪽으로 " +
                        "치우쳐 있고, 실제 어휘는 이보다 큽니다.",
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("아직 채점된 단어가 없습니다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "받아쓰기를 하면 맞춘 단어와 놓친 단어가 여기 쌓입니다. " +
                    "직접 체크하는 목록이 아니라 실제로 듣고 적은 결과만 반영합니다. " +
                    "안다고 생각하는 단어와 받아 적을 수 있는 단어는 다릅니다.",
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun BandCard(bands: List<VocabBandStat>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("빈도 구간별", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "영어에서 흔한 순서대로 묶은 구간입니다. 앞 구간이 비어 있으면 " +
                    "어려운 단어를 외우는 것보다 그쪽이 먼저입니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Band.entries.forEachIndexed { index, band ->
                BandRow(band, bands.firstOrNull { it.band == index })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BandRow(band: Band, stat: VocabBandStat?) {
    val met = stat?.met ?: 0
    val known = stat?.known ?: 0
    val ratio = if (met == 0) 0f else known.toFloat() / met
    val thin = met in 1 until VocabRepository.MIN_MET_PER_BAND

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(band.label, Modifier.width(74.dp), fontSize = 11.sp)
        Box(
            Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (ratio > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                met == 0 -> "-"
                // A ratio out of four words is noise; a percentage would give it
                // a confidence it has not earned.
                thin -> "$known/$met"
                else -> "${(ratio * 100).toInt()}%"
            },
            Modifier.width(48.dp),
            fontSize = 11.sp,
            color = if (thin) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RecommendCard(ui: VocabUi, vm: VocabViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("자료 난이도 맞추기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "가진 자료를 내 어휘와 대조해 모르는 단어 비율을 계산합니다. " +
                    "2~5%가 가장 좋습니다. 그보다 낮으면 새로 배울 것이 없고, " +
                    "10%를 넘으면 문맥으로 메울 수 없어 듣기가 무너집니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (ui.recommendations.isEmpty()) {
                Button(
                    onClick = { vm.scanLibrary() },
                    enabled = !ui.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ui.scanning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("분석 중")
                    } else {
                        Text("내 자료 분석하기")
                    }
                }
            } else {
                ui.recommendations.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.episode.title, fontSize = 13.sp, maxLines = 2)
                            Text(
                                r.unknown.verdict,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "%.1f%%".format(r.unknown.rate * 100),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = rateColor(r.unknown.rate),
                            )
                            r.episode.cefr?.let { Text(cefrLabel(it), fontSize = 10.sp) }
                        }
                    }
                }
                if (ui.truncated) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "최근 30개만 분석했습니다.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun rateColor(rate: Float): Color = when {
    rate <= 0.02f -> Color(0xFF2E7D32)
    rate <= 0.05f -> Color(0xFF1B5E20)
    rate <= 0.10f -> Color(0xFF9A6700)
    else -> Color(0xFFB3261E)
}

private fun bandLabel(rank: Int): String = if (rank == 0) "목록 밖" else "빈도 ${rank}위"

internal fun cefrLabel(name: String): String =
    runCatching { Cefr.valueOf(name).label }.getOrDefault(name)
