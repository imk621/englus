package com.englus.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englus.app.data.Diagnosis
import com.englus.app.data.SavedSentence
import com.englus.app.ui.common.ExplainSection
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ReviewViewModel = viewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val weakness by vm.weakness.collectAsStateWithLifecycle()
    val total by vm.totalAttempts.collectAsStateWithLifecycle()
    val correct by vm.correctAttempts.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모아보기") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (total > 0) {
                item { WeaknessCard(total, correct, weakness) }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = ui.filter == null,
                        onClick = { vm.setFilter(null) },
                        label = { Text("전체", fontSize = 12.sp) },
                    )
                    listOf(Diagnosis.SOUND, Diagnosis.VOCAB, Diagnosis.SPEED).forEach { d ->
                        FilterChip(
                            selected = ui.filter == d,
                            onClick = { vm.setFilter(d) },
                            label = { Text(label(d), fontSize = 12.sp) },
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                item {
                    Text(
                        if (ui.filter == null)
                            "저장된 문장이 없습니다. 플레이어에서 북마크하거나, 받아쓰기에서 틀린 문장이 자동으로 모입니다."
                        else "이 분류에 해당하는 문장이 없습니다.",
                        Modifier.padding(top = 24.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items, key = { it.sentence.id }) { item ->
                SavedCard(
                    item = item,
                    playing = ui.playingSentenceId == item.sentence.id,
                    expanded = ui.expandedSentenceId == item.sentence.id,
                    note = if (ui.expandedSentenceId == item.sentence.id) note else null,
                    explaining = ui.explaining && ui.expandedSentenceId == item.sentence.id,
                    explainError = if (ui.expandedSentenceId == item.sentence.id) ui.explainError else null,
                    explainConfigured = vm.explainConfigured,
                    onPlay = { vm.play(item) },
                    onRemove = { vm.remove(item) },
                    onToggleExpanded = { vm.toggleExpanded(item) },
                    onExplain = { vm.explain(item) },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun WeaknessCard(total: Int, correct: Int, weakness: List<com.englus.app.data.DiagnosisCount>) {
    val misses = weakness.sumOf { it.count }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("약점 리포트", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "받아쓰기 $total 문장 · 정답 $correct (${if (total > 0) correct * 100 / total else 0}%)",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))

            if (misses == 0) {
                Text("아직 틀린 문장이 없습니다.", fontSize = 13.sp)
            } else {
                listOf(Diagnosis.SOUND, Diagnosis.VOCAB, Diagnosis.SPEED).forEach { d ->
                    val n = weakness.firstOrNull { it.diagnosis == d }?.count ?: 0
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(label(d), Modifier.weight(1f), fontSize = 13.sp)
                        Text("$n  (${n * 100 / misses}%)", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(hint(weakness), fontSize = 12.sp)
            }
        }
    }
}

/**
 * The report is only useful if it says what to do next, and the right advice
 * differs completely by category — more vocabulary does nothing for a sound
 * problem, and slowing down does nothing for a missing word.
 */
private fun hint(weakness: List<com.englus.app.data.DiagnosisCount>): String {
    val top = weakness.maxByOrNull { it.count } ?: return ""
    return when (top.diagnosis) {
        Diagnosis.SOUND ->
            "실패의 대부분이 소리입니다. 읽으면 아는 문장을 못 듣고 있으니 어휘가 아니라 그 구간 반복이 필요합니다 — 연음, 축약, 약해진 기능어."
        Diagnosis.VOCAB ->
            "실패의 대부분이 어휘입니다. 재료가 지금 수준보다 어렵다는 신호이기도 하니, 한 단계 쉬운 팟캐스트를 함께 쓰는 편이 낫습니다."
        Diagnosis.SPEED ->
            "실패의 대부분이 속도입니다. 소리도 단어도 아는데 따라가지 못하는 상태라, 0.7배속에서 시작해 조금씩 올리면 좁혀집니다."
        Diagnosis.CORRECT -> ""
    }
}

@Composable
private fun SavedCard(
    item: SavedSentence,
    playing: Boolean,
    expanded: Boolean,
    note: com.englus.app.data.SentenceNote?,
    explaining: Boolean,
    explainError: String?,
    explainConfigured: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onToggleExpanded: () -> Unit,
    onExplain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.sentence.text, fontSize = 15.sp)
                    // Always visible here: the attempt is already over, and this
                    // list exists to close the gap the attempt exposed.
                    item.sentence.translation?.let { ko ->
                        Spacer(Modifier.height(2.dp))
                        Text(ko, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.diagnosis?.let { d ->
                            Text(
                                label(d),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(" · ", fontSize = 11.sp)
                        }
                        Text(
                            item.episodeTitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "듣기",
                        tint = if (playing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "목록에서 제거") }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                ExplainSection(
                    note = note,
                    loading = explaining,
                    error = explainError,
                    configured = explainConfigured,
                    onExplain = onExplain,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

private fun label(d: Diagnosis): String = when (d) {
    Diagnosis.SOUND -> "소리"
    Diagnosis.VOCAB -> "어휘"
    Diagnosis.SPEED -> "속도"
    Diagnosis.CORRECT -> "정답"
}
