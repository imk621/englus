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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
    showBack: Boolean = true,
    onOpenSettings: () -> Unit,
    onStartSession: () -> Unit,
    onSoundDrills: () -> Unit,
    onVocab: () -> Unit,
    vm: ReviewViewModel = viewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val weakness by vm.weakness.collectAsStateWithLifecycle()
    val total by vm.totalAttempts.collectAsStateWithLifecycle()
    val correct by vm.correctAttempts.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val dueCount by vm.dueCount.collectAsStateWithLifecycle()
    val totalCards by vm.totalCards.collectAsStateWithLifecycle()
    val reviewsToday by vm.reviewsToday.collectAsStateWithLifecycle()
    val logCount by vm.logCount.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모아보기") },
                navigationIcon = {
                    // A tab root has nowhere to go back to.
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (totalCards > 0) {
                item {
                    DueCard(
                        due = dueCount,
                        total = totalCards,
                        reviewedToday = reviewsToday,
                        onStart = onStartSession,
                    )
                }
            }

            if (total > 0) {
                item { WeaknessCard(total, correct, weakness) }
            }

            if (logCount >= 20) {
                item { ScheduleFitCard(ui, logCount, vm) }
            }

            item {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onVocab),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("어휘 프로필", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "받아쓰기에서 맞춘 단어와 놓친 단어가 쌓여 만들어집니다. " +
                                "모르는 단어를 흔한 순서로 보여주고, 가진 자료가 지금 " +
                                "나에게 맞는 난이도인지 계산합니다.",
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            if (items.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable(onClick = onSoundDrills),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("소리 패턴 훈련", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "저장된 문장을 축약·연음·약형 같은 소리 변형별로 묶어 반복합니다. " +
                                    "'소리' 진단을 받은 문장에 직접 대응하는 훈련입니다.",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
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

/**
 * Spaced repetition turns the saved list from a pile into a queue. Without it the
 * collection only grows: every dictation failure lands here and nothing ever
 * brings it back at the right moment.
 */
@Composable
private fun DueCard(due: Int, total: Int, reviewedToday: Int, onStart: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (due > 0) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (due > 0) "오늘 복습할 문장 ${due}개" else "지금 복습할 카드가 없습니다",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "전체 ${total}개 · 오늘 완료 ${reviewedToday}개",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (due > 0) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("복습 시작") }
            }
        }
    }
}

/**
 * Predicted versus observed recall, and a personal re-fit when the history can
 * support one.
 *
 * Shown only after enough reviews exist. Offering personalisation on ten reviews
 * would produce a confident number built on nothing.
 */
@Composable
private fun ScheduleFitCard(ui: ReviewUi, logCount: Int, vm: ReviewViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("복습 간격 점검", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (vm.personalised) "개인 가중치" else "기본 가중치",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("복습 기록 ${logCount}건", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            val analysis = ui.analysis
            if (analysis == null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.analyze() },
                    enabled = !ui.analyzing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ui.analyzing) "분석 중…" else "예측 대비 실제 확인") }
            } else {
                Spacer(Modifier.height(10.dp))
                analysis.calibration.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "예측 ${(b.predicted * 100).toInt()}%",
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                        )
                        Text(
                            "실제 ${(b.actual * 100).toInt()}%  (${b.count}건)",
                            fontSize = 12.sp,
                            color = if (b.actual + 0.05 < b.predicted)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(verdict(analysis), fontSize = 12.sp)

                val fit = analysis.optimization
                Spacer(Modifier.height(10.dp))
                if (fit == null) {
                    Text(
                        "개인 가중치를 적용하려면 복습 기록이 더 필요합니다. " +
                            "적은 데이터로 맞춘 값은 개인화가 아니라 잡음입니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "표본 ${fit.sampleCount}건 · 오차 %.3f → %.3f".format(fit.lossBefore, fit.lossAfter),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.applyPersonalWeights() },
                            enabled = fit.improved,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (fit.improved) "개인 가중치 적용" else "개선 없음") }
                        if (vm.personalised) {
                            OutlinedButton(onClick = { vm.resetWeights() }) { Text("되돌리기") }
                        }
                    }
                }
                ui.analysisMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun verdict(analysis: com.englus.app.srs.SrsAnalysis): String {
    val n = analysis.calibration.sumOf { it.count }
    if (n == 0) return ""
    val predicted = analysis.calibration.sumOf { it.predicted * it.count } / n
    val actual = analysis.calibration.sumOf { it.actual * it.count } / n
    return when {
        actual + 0.07 < predicted ->
            "실제로 잊는 비율이 예측보다 높습니다. 간격이 지금 조금 깁니다."
        actual > predicted + 0.07 ->
            "예측보다 잘 기억하고 있습니다. 간격을 늘려도 됩니다."
        else -> "예측과 실제가 잘 맞습니다. 지금 간격이 적절합니다."
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
