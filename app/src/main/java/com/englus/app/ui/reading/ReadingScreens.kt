package com.englus.app.ui.reading

import android.app.Application
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englus.app.data.Sentence
import com.englus.app.ui.common.ExplainSection
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingCaptureScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    /** Text handed over by another app through the share sheet. */
    initialText: String? = null,
    vm: ReadingCaptureViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Filled in once. Re-applying on every recomposition would fight the user
    // as they edit what was shared.
    LaunchedEffect(initialText) {
        if (!initialText.isNullOrBlank()) vm.setPasted(initialText)
    }

    // Kept in composition so the camera result can be read back from the same
    // file the contract was handed.
    val captureUri = remember {
        val file = vm.cameraFile()
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) vm.importImage(captureUri) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.importImage(it) } }

    ui.createdId?.let { id ->
        // Navigate once, then clear so returning here does not bounce again.
        vm.consumeCreated()
        onOpen(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("읽기 추가") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "사진 속 영어를 읽어 문장으로 나눕니다. 인식은 전부 기기 안에서 처리됩니다.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { takePhoto.launch(captureUri) },
                    enabled = ui.busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                    Text("  사진 찍기")
                }
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = ui.busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                    Text("  갤러리")
                }
            }

            Text("또는 직접 붙여넣기", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            OutlinedTextField(
                value = ui.pasted,
                onValueChange = vm::setPasted,
                label = { Text("영어 텍스트") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )
            Button(
                onClick = { vm.importPasted() },
                enabled = ui.pasted.isNotBlank() && ui.busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("가져오기") }

            ui.busy?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
            ui.error?.let {
                Card(
                    Modifier.fillMaxWidth().clickable { vm.dismissError() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) { Text(it, Modifier.padding(12.dp), fontSize = 12.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val episode by vm.episode.collectAsStateWithLifecycle()
    val sentences by vm.sentences.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(episode?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    TextButton(onClick = { vm.toggleAllTranslations() }) {
                        Text(if (ui.showAll) "해석 숨기기" else "해석 모두 보기", fontSize = 12.sp)
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sentences, key = { it.id }) { s ->
                ReaderRow(
                    sentence = s,
                    showTranslation = ui.showAll || s.id in ui.translated,
                    detailOpen = ui.detailSentenceId == s.id,
                    note = if (ui.detailSentenceId == s.id) note else null,
                    explaining = ui.explaining,
                    explainError = ui.explainError,
                    explainConfigured = vm.explainConfigured,
                    onTap = { vm.toggleTranslation(s.id) },
                    onToggleDetail = { vm.toggleDetail(s.id) },
                    onExplain = { vm.explain(s) },
                    onOpenSettings = onOpenSettings,
                    onToggleSaved = { vm.toggleSaved(s) },
                )
            }
        }
    }
}

@Composable
private fun ReaderRow(
    sentence: Sentence,
    showTranslation: Boolean,
    detailOpen: Boolean,
    note: com.englus.app.data.SentenceNote?,
    explaining: Boolean,
    explainError: String?,
    explainConfigured: Boolean,
    onTap: () -> Unit,
    onToggleDetail: () -> Unit,
    onExplain: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    sentence.text,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable(onClick = onTap),
                )
                if (showTranslation) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sentence.translation ?: "해석 준비 중…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (!detailOpen) {
                        TextButton(onClick = onToggleDetail) { Text("자세히", fontSize = 12.sp) }
                    } else {
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
            IconButton(onClick = onToggleSaved, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (sentence.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "복습에 저장",
                    tint = if (sentence.saved) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
