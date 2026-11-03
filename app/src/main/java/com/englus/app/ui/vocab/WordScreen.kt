package com.englus.app.ui.vocab

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.englus.app.data.VocabState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordScreen(vm: WordViewModel, onBack: () -> Unit, onDictate: (Long, Int) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.lemma) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    if (ui.word?.state != VocabState.KNOWN) {
                        TextButton(onClick = vm::markKnown) { Text("알아요", fontSize = 13.sp) }
                    }
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header(ui) }

            if (ui.loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("문장 찾는 중", fontSize = 12.sp)
                    }
                }
            } else if (ui.sentences.isEmpty()) {
                item { NotFoundCard() }
            } else {
                items(ui.sentences.size) { i ->
                    val item = ui.sentences[i]
                    SentenceCard(
                        text = item.sentence.text,
                        translation = item.sentence.translation,
                        episodeTitle = item.episodeTitle,
                        hasAudio = item.audioPath.isNotBlank(),
                        playing = ui.playingId == item.sentence.id,
                        saved = item.sentence.id in ui.savedIds,
                        onPlay = { vm.play(item) },
                        onSlow = { vm.play(item, slow = true) },
                        onSave = { vm.toggleSaved(item) },
                        onDictate = { onDictate(item.sentence.episodeId, item.sentence.position) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(ui: WordUi) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            val word = ui.word
            Text(
                when {
                    word == null -> "이 단어"
                    word.rank == 0 -> "빈도 목록 밖의 단어"
                    else -> "영어에서 ${word.rank}번째로 흔한 단어"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            word?.let {
                Spacer(Modifier.height(2.dp))
                Text("만난 ${it.seen}번 중 맞춘 ${it.correct}번", fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            // Why this screen shows sentences instead of a definition.
            Text(
                "이 단어가 실제로 나온 문장들입니다. 단어장으로 외우는 것보다 " +
                    "놓쳤던 그 목소리, 그 맥락에서 다시 만나는 편이 다음에 들립니다.",
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun NotFoundCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("이 단어가 나온 문장을 못 찾았습니다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "그 에피소드를 지웠거나, 불규칙 변화형이라 검색에 걸리지 않았을 수 있습니다 " +
                    "(went 를 go 로 되돌리지는 못합니다).",
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SentenceCard(
    text: String,
    translation: String?,
    episodeTitle: String,
    hasAudio: Boolean,
    playing: Boolean,
    saved: Boolean,
    onPlay: () -> Unit,
    onSlow: () -> Unit,
    onSave: () -> Unit,
    onDictate: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(text, fontSize = 15.sp)
            translation?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(episodeTitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasAudio) {
                    IconButton(onClick = onPlay) {
                        Icon(
                            Icons.Default.PlayArrow,
                            "듣기",
                            tint = if (playing) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onSlow) { Text("느리게", fontSize = 12.sp) }
                    TextButton(onClick = onDictate) { Text("받아쓰기", fontSize = 12.sp) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSave) {
                    Icon(
                        if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        if (saved) "복습에서 빼기" else "복습에 넣기",
                        tint = if (saved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
