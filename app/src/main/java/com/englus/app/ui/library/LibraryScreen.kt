package com.englus.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englus.app.data.Episode
import com.englus.app.data.TranscriptState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Episode) -> Unit,
    onPodcasts: () -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    onWriting: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    // The key is entered on another screen; re-check on resume so the prompt
    // disappears the moment the user comes back having set it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshGemini() }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importAudio) }

    // "*/*" because ggml weights have no registered MIME type; a narrower filter
    // hides the .bin in the picker entirely.
    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importModel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("englus") },
                actions = {
                    IconButton(onClick = onWriting) {
                        Icon(Icons.Default.EditNote, "영작")
                    }
                    IconButton(onClick = onReview) {
                        Icon(Icons.Default.Bookmarks, "모아보기")
                    }
                    IconButton(onClick = onPodcasts) {
                        Icon(Icons.Default.Podcasts, "팟캐스트")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "설정")
                    }
                },
            )
        },
        floatingActionButton = {
            if (ui.models.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { pickAudio.launch(arrayOf("audio/*")) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("오디오 추가") },
                )
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.models.isEmpty()) {
                item { ModelSetupCard(onPick = { pickModel.launch(arrayOf("*/*")) }) }
            }

            // Only once something has actually been transcribed: the app is fully
            // usable without a key, so prompting on an empty library would be
            // asking for setup before showing what it buys.
            if (!ui.geminiConfigured && !ui.geminiPromptDismissed &&
                episodes.any { it.state == TranscriptState.DONE }
            ) {
                item {
                    GeminiPromptCard(
                        onOpen = onSettings,
                        onDismiss = { vm.dismissGeminiPrompt() },
                    )
                }
            }

            ui.busy?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(msg, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { ui.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            ui.error?.let { err ->
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { vm.dismissError() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Text(
                            err,
                            Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (episodes.isEmpty() && ui.models.isNotEmpty()) {
                item {
                    Text(
                        "오디오를 추가하면 백그라운드에서 전사한 뒤 문장 단위로 들을 수 있습니다.",
                        Modifier.padding(top = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            items(episodes, key = { it.id }) { ep ->
                EpisodeCard(
                    episode = ep,
                    onOpen = { if (ep.state == TranscriptState.DONE) onOpen(ep) },
                    onRetry = { vm.retry(ep) },
                    onDelete = { vm.delete(ep) },
                )
            }
        }
    }
}

@Composable
private fun GeminiPromptCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("문장 설명을 켜시겠습니까?", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Gemini API 키를 넣으면 문장마다 '자세히'를 눌러 정확한 해석, " +
                    "왜 안 들렸는지(연음·축약), 문법 설명을 볼 수 있습니다.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "무료로 발급받을 수 있고, 키 없이도 전사·해석·받아쓰기는 그대로 동작합니다. " +
                    "여러 사람이 쓴다면 각자 자기 키를 쓰세요.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("설정하기")
                }
                TextButton(onClick = onDismiss) { Text("나중에", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ModelSetupCard(onPick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("먼저 whisper 모델이 필요합니다", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "전사는 전부 이 기기에서 처리됩니다. 서버도, 통신도, 비용도 없습니다.\n\n" +
                    "브라우저에서 아래 파일을 내려받은 뒤 '모델 가져오기'로 불러오세요.\n" +
                    "huggingface.co/ggerganov/whisper.cpp → ggml-base.en.bin (142MB)",
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text("모델 가져오기 (.bin)")
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(episode.title, Modifier.weight(1f), fontSize = 15.sp, maxLines = 2)
                if (episode.state == TranscriptState.FAILED) {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "다시 시도") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제") }
            }

            Spacer(Modifier.height(4.dp))

            when (episode.state) {
                TranscriptState.PENDING -> Status("전사 대기 중")
                TranscriptState.RUNNING -> {
                    Status("전사 중 ${(episode.progress * 100).toInt()}%")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { episode.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TranscriptState.DONE -> Status(
                    buildString {
                        append(formatDuration(episode.durationMs))
                        episode.modelName?.let { append(" · ${it.removePrefix("ggml-").removeSuffix(".bin")}") }
                    }
                )
                TranscriptState.FAILED -> Text(
                    episode.error ?: "전사 실패",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d분 %02d초".format(total / 60, total % 60)
}
