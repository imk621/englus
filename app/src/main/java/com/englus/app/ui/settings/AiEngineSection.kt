package com.englus.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englus.app.ai.AiEngine
import com.englus.app.ai.OnDeviceGemini.Status

/**
 * Where generated text comes from.
 *
 * The status line is the important part: on an S25 Nano may still need its
 * one-time AICore download, and on an emulator it is simply absent. Saying so
 * here is what keeps "자동" from looking like it silently used the cloud.
 */
@Composable
fun AiEngineSection(
    ui: SettingsUi,
    onEngine: (AiEngine) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 엔진", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "Gemini Nano: ${ui.nano.label}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ui.nano == Status.AVAILABLE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "문장 설명, 영작 첨삭, 작문 주제, 오늘의 문장을 휴대폰 안의 Gemini Nano 로 " +
                    "먼저 처리합니다. 무료이고, 인터넷이 없어도 되고, 글이 기기 밖으로 나가지 않습니다. " +
                    "Nano 가 없거나 답이 형식에 맞지 않으면 Gemini API 로 넘깁니다. " +
                    "듣기 시험은 '그럴듯하게 잘못 들은' 오답을 만들어야 해서 클라우드를 먼저 씁니다.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))

            AiEngine.entries.forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = ui.engine == e, onClick = { onEngine(e) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = ui.engine == e, onClick = { onEngine(e) })
                    Text(e.label, fontSize = 13.sp)
                }
            }

            ui.nanoDownloaded?.let { bytes ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("내려받는 중… ${bytes / 1_000_000} MB", fontSize = 11.sp)
            }
            ui.nanoError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f)) {
                    Text("상태 확인", fontSize = 12.sp)
                }
                if (ui.nano == Status.DOWNLOADABLE || ui.nano == Status.DOWNLOADING) {
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = ui.nanoDownloaded == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Nano 내려받기", fontSize = 12.sp) }
                }
            }
            if (ui.nano == Status.UNAVAILABLE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "에뮬레이터와 AICore 가 없는 기기에서는 항상 '지원하지 않음'입니다. " +
                        "Galaxy S25 에서는 AICore 앱 업데이트 후 다시 확인하세요.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
