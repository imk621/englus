package com.englus.app.ui.speaking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Button

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakingHomeScreen(
    vm: SpeakingHomeViewModel,
    onShadow: (Long) -> Unit,
    onShadowMissed: () -> Unit,
    onLive: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("말하기") }) }) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShadowingIntro(ui) }

            if (ui.missedCount > 0) {
                item { MissedCard(ui.missedCount, onShadowMissed) }
                item { HandsFreeCard(ui.missedCount) { vm.startHandsFree() } }
            }

            if (!ui.modelReady) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Text(
                            "whisper 모델이 필요합니다. 듣기 탭에서 먼저 가져오세요. " +
                                "내 발음을 받아 적는 것도 같은 모델이 합니다.",
                            Modifier.padding(14.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (ui.targets.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("따라 말할 자료가 없습니다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "듣기 탭에서 팟캐스트를 받거나 오디오를 넣고 전사가 끝나면 " +
                                    "여기 나타납니다. 원어민이 실제로 말한 문장이라야 " +
                                    "따라 말하는 의미가 있습니다.",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            } else {
                item {
                    Text("자료를 고르세요", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                items(ui.targets.size) { i ->
                    val t = ui.targets[i]
                    Card(
                        Modifier.fillMaxWidth().clickable { onShadow(t.episode.id) },
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.episode.title, fontSize = 14.sp, maxLines = 2)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (t.attempts == 0) "아직 안 해봤습니다"
                                    else "${t.attempts}문장 연습함",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            t.average?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "평균 ${(it * 100).toInt()}%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            item { LiveCard(ui, onLive) }
        }
    }
}

/**
 * The queue worth doing first.
 *
 * Walking an episode from sentence one spends most of the session on lines that
 * were already easy. The app has known all along which sentences were failed;
 * saying those out loud is where the time goes furthest.
 */
@Composable
private fun MissedCard(count: Int, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("놓친 문장 ${count}개 따라 말하기", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "받아쓰기에서 틀렸거나 직접 저장한 문장만 모아 따라 말합니다. " +
                    "에피소드를 처음부터 훑으면 이미 쉬웠던 문장에 시간을 다 씁니다.",
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Speaking practice for the hours a screen cannot be used.
 *
 * Scored shadowing needs both hands and eyes, which confines it to sitting down.
 * The times a learner can actually say things out loud - walking, cooking,
 * commuting alone - are exactly the times they cannot look at anything.
 */
@Composable
private fun HandsFreeCard(count: Int, onStart: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("핸즈프리로 따라 말하기", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "문장 하나 → 따라 말할 만큼의 빈 시간 → 다음 문장. 화면을 꺼도 계속되고 " +
                    "잠금화면에 문장이 뜹니다. 이어폰을 끼고 주머니에 넣은 채로 " +
                    "걸으면서 하는 용도입니다.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            // Saying what it does not do matters as much: a number produced from
            // a recording made in traffic would measure the street.
            Text(
                "채점은 없습니다. 길에서 녹음한 것을 채점하면 발음이 아니라 소음을 " +
                    "재게 됩니다. 점수가 필요하면 위의 쉐도잉을 쓰세요.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onStart(); started = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (started) "재생 중 · 잠금화면에서 조작하세요" else "${count}개로 시작") }
        }
    }
}

@Composable
private fun ShadowingIntro(ui: SpeakingHomeUi) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("쉐도잉", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text("무료 · 인터넷 없이 · 기기 안에서", fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "원어민 문장을 듣고 따라 말하면, 전사에 쓴 것과 같은 whisper 모델이 " +
                    "내 발음을 받아 적어 원문과 비교합니다. 점수보다 중요한 것은 " +
                    "'내 발음으로 들린 것'입니다. 어디를 고쳐야 하는지 정확히 짚어줍니다.",
                fontSize = 12.sp,
            )
            if (ui.totalAttempts > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "지금까지 ${ui.totalAttempts}문장" +
                        (ui.overallAverage?.let { " · 평균 ${(it * 100).toInt()}%" } ?: ""),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * The paid option, last and clearly labelled.
 *
 * Free-of-charge on the Live models covers text input only; the audio that makes
 * a conversation is billed by the minute. Saying so here is the difference
 * between an option and a trap.
 */
@Composable
private fun LiveCard(ui: SpeakingHomeUi, onLive: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onLive)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("실시간 회화", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (ui.liveConfigured && ui.liveEnabled) "사용 가능" else "선택 · 유료",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Gemini와 음성으로 대화합니다. 이 앱에서 유일하게 실제로 요금이 나가는 " +
                    "기능입니다 — 무료 등급은 텍스트 입력만 덮고, 대화의 본체인 음성은 " +
                    "분 단위로 과금됩니다.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "안 써도 됩니다. 쉐도잉만으로도 발음과 리듬은 충분히 훈련됩니다. " +
                    "회화가 더 잘 다루는 것은 '멈추지 않고 이어 말하기'입니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
