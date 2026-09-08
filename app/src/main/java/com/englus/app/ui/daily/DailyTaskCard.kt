package com.englus.app.ui.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.data.DailySentence

/**
 * Today's task, shown at the top of writing and speaking.
 *
 * The English half is never revealed here. A model answer visible beside the
 * prompt turns production into copying, and the point of generating around this
 * learner's own weak words is that they have to reach for them.
 */
@Composable
fun DailyTaskCard(
    vm: DailyViewModel,
    /** "말하기" or "영작": the same sentence, two different things to do with it. */
    actionLabel: String,
    onStart: (DailySentence) -> Unit,
    forSpeaking: Boolean = false,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()

    val pending = if (forSpeaking) vm.nextForSpeaking() else vm.nextForWriting()
    val doneCount = today.count { if (forSpeaking) it.spokenAt != null else it.writtenAt != null }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("오늘의 문장", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (today.isNotEmpty()) {
                    Text(
                        "$doneCount / ${today.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "내가 놓친 단어와 반복하는 오류로 만든 문장입니다. 팟캐스트는 " +
                    "내 약점을 골라 쓰지 않으니, 이건 생성이 실제로 잘하는 일입니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ui.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::dismissError) { Text("확인", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(12.dp))

            when {
                !ui.configured -> Text(
                    "Gemini API 키가 필요합니다. 설정에서 넣으세요. " +
                        "무료 등급으로 충분합니다 - 하루 한 번 요청입니다.",
                    fontSize = 12.sp,
                )

                today.isEmpty() -> Button(
                    onClick = { vm.generate() },
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ui.busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("만드는 중")
                    } else {
                        Text("오늘의 문장 만들기")
                    }
                }

                pending == null -> Column {
                    Text("오늘 것은 다 했습니다.", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.generate(force = true) },
                        enabled = !ui.busy,
                    ) { Text("더 만들기", fontSize = 12.sp) }
                }

                else -> Column {
                    Text(pending.korean, fontSize = 17.sp)
                    if (pending.words.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "노리는 단어: " + pending.words.joinToString(", "),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onStart(pending) },
                            modifier = Modifier.weight(1f),
                        ) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}
