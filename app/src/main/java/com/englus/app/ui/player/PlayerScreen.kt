package com.englus.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.data.Sentence
import com.englus.app.data.SentenceNote
import com.englus.app.ui.common.ExplainSection
import androidx.compose.material3.TextButton

/** Everything the expanded sentence needs to render its cloud explanation. */
data class SentenceDetail(
    val note: SentenceNote?,
    val loading: Boolean,
    val error: String?,
    val onExplain: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onDictation: (Int) -> Unit,
    onShadowing: (Int) -> Unit,
    onExam: () -> Unit,
    onCheckTranscript: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val episode by vm.episode.collectAsStateWithLifecycle()
    val sentences by vm.sentences.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()

    val current = remember(sentences, ui.positionMs) { vm.currentSentence(ui.positionMs) }
    val listState = rememberLazyListState()

    LaunchedEffect(current?.position, ui.autoScroll) {
        val pos = current?.position ?: return@LaunchedEffect
        if (ui.autoScroll) listState.animateScrollToItem(pos)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(episode?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "연습")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // Drills start from the sentence being listened to, not the
                        // top of the episode: practice is most useful right where
                        // something was missed.
                        DropdownMenuItem(
                            text = { Text("받아쓰기") },
                            onClick = { menuOpen = false; onDictation(current?.position ?: 0) },
                        )
                        DropdownMenuItem(
                            text = { Text("쉐도잉") },
                            onClick = { menuOpen = false; onShadowing(current?.position ?: 0) },
                        )
                        DropdownMenuItem(
                            text = { Text("듣기 시험") },
                            onClick = { menuOpen = false; onExam() },
                        )
                        DropdownMenuItem(
                            text = { Text("전사 정확도 확인") },
                            onClick = { menuOpen = false; onCheckTranscript() },
                        )
                    }
                },
            )
        },
        bottomBar = { Controls(vm, ui) },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(sentences, key = { _, s -> s.id }) { _, s ->
                SentenceRow(
                    sentence = s,
                    isCurrent = s.id == current?.id,
                    positionMs = ui.positionMs,
                    hidden = ui.hideText && s.id !in ui.revealed,
                    showTranslation = s.id in ui.translated,
                    detail = if (ui.detailSentenceId == s.id) {
                        SentenceDetail(
                            note = note,
                            loading = ui.explaining,
                            error = ui.explainError,
                            onExplain = { vm.explain(s) },
                        )
                    } else null,
                    explainConfigured = vm.explainConfigured,
                    onToggleDetail = { vm.toggleDetail(s.id) },
                    onOpenSettings = onOpenSettings,
                    onTap = { vm.seekToSentence(s) },
                    onReveal = { vm.reveal(s.id) },
                    onToggleTranslation = { vm.toggleTranslation(s.id) },
                    onToggleSaved = { vm.toggleSaved(s) },
                    wordIndex = { vm.currentWordIndex(s, ui.positionMs) },
                )
            }
        }
    }
}

@Composable
private fun SentenceRow(
    sentence: Sentence,
    isCurrent: Boolean,
    positionMs: Long,
    hidden: Boolean,
    showTranslation: Boolean,
    detail: SentenceDetail?,
    explainConfigured: Boolean,
    onToggleDetail: () -> Unit,
    onOpenSettings: () -> Unit,
    onTap: () -> Unit,
    onReveal: () -> Unit,
    onToggleTranslation: () -> Unit,
    onToggleSaved: () -> Unit,
    wordIndex: () -> Int,
) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTime(sentence.startMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))

                if (hidden) {
                    // Masked, not removed: the shape of the line still tells you
                    // how much you missed, and one tap settles it.
                    Text(
                        "•".repeat(sentence.text.length.coerceAtMost(60)),
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onReveal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isCurrent && sentence.words.isNotEmpty()) {
                    Text(
                        karaoke(sentence, wordIndex()),
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onToggleTranslation),
                    )
                } else {
                    Text(
                        sentence.text,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onToggleTranslation),
                    )
                }

                // Third tap state: hidden -> English -> Korean. Reaching the
                // translation takes a deliberate step, so it cannot pre-empt the
                // attempt to hear the line.
                if (!hidden && showTranslation) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sentence.translation ?: "해석 준비 중…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (detail == null) {
                        TextButton(onClick = onToggleDetail) { Text("자세히", fontSize = 12.sp) }
                    } else {
                        ExplainSection(
                            note = detail.note,
                            loading = detail.loading,
                            error = detail.error,
                            configured = explainConfigured,
                            onExplain = detail.onExplain,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }

            IconButton(onClick = onToggleSaved, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (sentence.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "못 알아들은 문장 저장",
                    tint = if (sentence.saved) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun karaoke(sentence: Sentence, activeIndex: Int) = buildAnnotatedString {
    sentence.words.forEachIndexed { i, w ->
        if (i > 0) append(" ")
        if (i == activeIndex) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(w.text) }
        } else {
            append(w.text)
        }
    }
}

@Composable
private fun Controls(vm: PlayerViewModel, ui: PlayerUi) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                // targetSdk 35 means Android 15 draws this edge to edge; without
                // the inset the transport buttons sit under the system nav bar
                // and both become unusable.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {

            if (ui.showSync) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("자막 싱크", fontSize = 12.sp)
                    OutlinedButton(onClick = { vm.adjustSync(-100) }) { Text("−100ms") }
                    Text(
                        "%+d ms".format(ui.syncOffsetMs),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { vm.resetSync() },
                    )
                    OutlinedButton(onClick = { vm.adjustSync(+100) }) { Text("+100ms") }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f).forEach { s ->
                    FilterChip(
                        selected = ui.speed == s,
                        onClick = { vm.setSpeed(s) },
                        label = { Text(if (s == 1f) "1x" else "${s}x", fontSize = 11.sp) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.toggleHideText() }) {
                    Icon(
                        if (ui.hideText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "자막 가리기",
                        tint = if (ui.hideText) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.step(-1) }) {
                    Icon(Icons.Default.SkipPrevious, "이전 문장")
                }
                FilledIconButton(
                    onClick = { vm.playPause() },
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "재생/일시정지",
                    )
                }
                IconButton(onClick = { vm.step(1) }) {
                    Icon(Icons.Default.SkipNext, "다음 문장")
                }
                IconButton(onClick = { vm.toggleRepeat() }) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = "문장 반복",
                        tint = if (ui.repeatSentence) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.toggleSync() }) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "자막 싱크 조정",
                        tint = if (ui.syncOffsetMs != 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
