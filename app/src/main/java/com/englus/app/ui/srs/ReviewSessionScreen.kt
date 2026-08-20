package com.englus.app.ui.srs

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
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englus.app.data.Diagnosis
import com.englus.app.srs.Rating

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSessionScreen(onBack: () -> Unit, vm: ReviewSessionViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val due by vm.card.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (ui.total > 0) "복습  ${ui.index + 1} / ${ui.total}" else "복습")
                },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                ui.loading -> Text("불러오는 중…", fontSize = 13.sp)

                ui.done -> {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        if (ui.reviewed > 0) "오늘 복습 완료" else "지금 복습할 카드가 없습니다",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (ui.reviewed > 0) "${ui.reviewed}개를 복습했습니다."
                        else "받아쓰기에서 틀리거나 플레이어에서 북마크한 문장이 여기로 모입니다.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                }

                due != null -> {
                    val card = due!!
                    LinearProgressIndicator(
                        progress = { (ui.index + 1).toFloat() / ui.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Front of the card: sound only. Reading it would review the
                    // wrong thing — the sentence was missed by ear.
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Reading material has no audio, so its card front is
                            // the sentence itself and the back is the meaning. A
                            // silent play button would just be broken.
                            if (card.audioPath.isBlank()) {
                                Text("뜻을 떠올려 보세요", fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    card.text,
                                    fontSize = 17.sp,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Text("들어보세요", fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                FilledIconButton(onClick = { vm.play() }, modifier = Modifier.size(64.dp)) {
                                    Icon(Icons.Default.Replay, "다시 듣기", Modifier.size(30.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                card.episodeTitle,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }

                    if (!ui.revealed) {
                        Button(
                            onClick = { vm.reveal() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("정답 보기") }
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                // Already shown on the front for reading cards.
                                if (card.audioPath.isNotBlank()) {
                                    Text(card.text, fontSize = 17.sp)
                                }
                                card.translation?.let {
                                    Spacer(Modifier.height(6.dp))
                                    Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                card.diagnosis?.let { d ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "지난 실패 원인: ${label(d)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Text("얼마나 잘 들렸습니까?", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        // Intervals are shown on the buttons so the grade is an
                        // informed choice rather than a guess about consequences.
                        GradeRow(ui.intervals) { vm.grade(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeRow(intervals: Map<Rating, Int>, onGrade: (Rating) -> Unit) {
    val specs = listOf(
        Triple(Rating.AGAIN, "다시", Color(0xFFB3261E)),
        Triple(Rating.HARD, "어려움", Color(0xFF7D5260)),
        Triple(Rating.GOOD, "좋음", Color(0xFF1B5E20)),
        Triple(Rating.EASY, "쉬움", Color(0xFF0D47A1)),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        specs.forEach { (rating, label, color) ->
            Button(
                onClick = { onGrade(rating) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = color),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp, vertical = 10.dp,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 13.sp, color = Color.White)
                    intervals[rating]?.let {
                        Text(formatInterval(it), fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

private fun formatInterval(days: Int): String = when {
    days < 30 -> "${days}일"
    days < 365 -> "${days / 30}개월"
    else -> "${days / 365}년"
}

private fun label(d: Diagnosis): String = when (d) {
    Diagnosis.SOUND -> "소리"
    Diagnosis.VOCAB -> "어휘"
    Diagnosis.SPEED -> "속도"
    Diagnosis.CORRECT -> "정답"
}
