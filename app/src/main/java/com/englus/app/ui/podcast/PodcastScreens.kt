package com.englus.app.ui.podcast

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.englus.app.data.DownloadState
import com.englus.app.data.FeedItem
import com.englus.app.data.PRESET_FEEDS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    vm: SubscriptionsViewModel = viewModel(),
) {
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("팟캐스트") },
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
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ui.url,
                        onValueChange = vm::setUrl,
                        label = { Text("RSS 주소", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { vm.subscribe(ui.url) }, enabled = ui.busy == null) {
                        Text("구독")
                    }
                }
            }

            ui.busy?.let { item { Text(it, fontSize = 12.sp) } }

            ui.error?.let { err ->
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { vm.dismissError() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) { Text(err, Modifier.padding(12.dp), fontSize = 12.sp) }
                }
            }

            if (podcasts.isNotEmpty()) {
                item { SectionTitle("구독 중") }
                items(podcasts, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title, Modifier.weight(1f), fontSize = 15.sp)
                            IconButton(onClick = { vm.unsubscribe(p) }) {
                                Icon(Icons.Default.Delete, "구독 해지")
                            }
                        }
                    }
                }
            }

            item { SectionTitle("추천 — 난이도 순") }
            item {
                Text(
                    "쉬운 것부터 시작해서 미지 단어가 줄어들면 올라가세요. " +
                        "너무 어려운 재료는 듣기 훈련이 아니라 좌절 연습이 됩니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(PRESET_FEEDS, key = { it.feedUrl }) { preset ->
                val already = podcasts.any { it.feedUrl == preset.feedUrl }
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !already) { vm.subscribe(preset.feedUrl) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (already) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.title, fontSize = 14.sp)
                            Text(
                                preset.level,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (already) Icon(Icons.Default.CheckCircle, "구독 중")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(vm: PodcastViewModel, onBack: () -> Unit) {
    val podcast by vm.podcast.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(podcast?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "새로고침") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.refreshing) item { Text("새로고침 중…", fontSize = 12.sp) }
            ui.error?.let { err ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) { Text(err, Modifier.padding(12.dp), fontSize = 12.sp) }
                }
            }
            items(items, key = { it.id }) { item -> FeedItemCard(item) { vm.download(item) } }
        }
    }
}

@Composable
private fun FeedItemCard(item: FeedItem, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 14.sp, maxLines = 3)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            if (item.publishedAt > 0) append(DATE.format(Date(item.publishedAt)))
                            if (item.durationSec > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${item.durationSec / 60}분")
                            }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    item.episodeId != null ->
                        Icon(Icons.Default.CheckCircle, "받음", tint = MaterialTheme.colorScheme.primary)
                    item.downloadState == DownloadState.DOWNLOADING ||
                        item.downloadState == DownloadState.QUEUED ->
                        Text("${(item.downloadProgress * 100).toInt()}%", fontSize = 12.sp)
                    else ->
                        IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "받기") }
                }
            }

            if (item.downloadState == DownloadState.DOWNLOADING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (item.downloadState == DownloadState.FAILED) {
                Text(
                    item.error ?: "다운로드 실패",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
}

private val DATE = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
