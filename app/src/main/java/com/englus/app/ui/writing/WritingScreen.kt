package com.englus.app.ui.writing

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
import androidx.compose.material.icons.filled.Delete
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englus.app.data.MistakeCategory
import com.englus.app.data.MistakeCount
import com.englus.app.data.Writing
import com.englus.app.data.WritingMistake
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenSettings: () -> Unit,
    vm: WritingViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val writings by vm.writings.collectAsStateWithLifecycle()
    val counts by vm.mistakeCounts.collectAsStateWithLifecycle()
    val mistakes by vm.mistakes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("영작") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!vm.configured) {
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Gemini API 키가 필요합니다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "영작 첨삭은 기기 안에서 처리할 수 없습니다. 듣기와 달리 " +
                                    "문법 설명과 자연스러운 대안을 만들어야 하기 때문입니다. " +
                                    "탭해서 설정하세요.",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            if (counts.isNotEmpty()) {
                item { MistakeReport(counts) }
            }

            item { Editor(ui, vm) }

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

            val focusId = ui.resultId ?: ui.expandedId
            items(writings, key = { it.id }) { w ->
                WritingCard(
                    writing = w,
                    expanded = w.id == focusId,
                    mistakes = if (w.id == focusId) mistakes else emptyList(),
                    onToggle = { vm.toggleExpanded(w.id) },
                    onDelete = { vm.delete(w) },
                )
            }
        }
    }
}

@Composable
private fun Editor(ui: WritingUi, vm: WritingViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("오늘의 영작", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.suggestPrompt() }, enabled = vm.configured && ui.busy == null) {
                    Text("주제 받기", fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = ui.prompt,
                onValueChange = vm::setPrompt,
                label = { Text("주제 (비워도 됩니다)", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )
            OutlinedTextField(
                value = ui.text,
                onValueChange = vm::setText,
                label = { Text("영어로 써보세요") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )

            ui.busy?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

            Button(
                onClick = { vm.correct() },
                enabled = vm.configured && ui.text.isNotBlank() && ui.busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("첨삭 받기") }
        }
    }
}

/**
 * The point of storing categories: one correction is noise, forty of them is a
 * pattern, and the pattern is what tells the learner what to study.
 */
@Composable
private fun MistakeReport(counts: List<MistakeCount>) {
    val total = counts.sumOf { it.count }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("자주 틀리는 것", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            counts.take(5).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(label(c.category), Modifier.weight(1f), fontSize = 13.sp)
                    Text("${c.count}회  (${c.count * 100 / total}%)", fontSize = 13.sp)
                }
            }
            counts.firstOrNull()?.let {
                Spacer(Modifier.height(8.dp))
                Text(advice(it.category), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WritingCard(
    writing: Writing,
    expanded: Boolean,
    mistakes: List<WritingMistake>,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (writing.prompt.isNotBlank()) {
                        Text(
                            writing.prompt,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(
                        writing.userText,
                        fontSize = 14.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        DATE.format(Date(writing.createdAt)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제") }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Section("교정", writing.correctedText)
                // Kept distinct from the correction on purpose: "correct" and
                // "what a native speaker would write" are different targets, and
                // only seeing one of them leaves the learner stuck.
                Section("자연스럽게", writing.naturalText)

                if (mistakes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("고친 곳", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    mistakes.forEach { m ->
                        Spacer(Modifier.height(6.dp))
                        Column {
                            Text(
                                "[${label(m.category)}]  ${m.original}  →  ${m.corrected}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(m.explanation, fontSize = 12.sp)
                        }
                    }
                }

                Section("총평", writing.overall)
            }
        }
    }
}

@Composable
private fun Section(label: String, value: String) {
    if (value.isBlank()) return
    Spacer(Modifier.height(8.dp))
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Text(value, fontSize = 14.sp)
}

private fun label(c: MistakeCategory): String = when (c) {
    MistakeCategory.ARTICLE -> "관사"
    MistakeCategory.TENSE -> "시제"
    MistakeCategory.PREPOSITION -> "전치사"
    MistakeCategory.AGREEMENT -> "수일치"
    MistakeCategory.PLURAL -> "복수형"
    MistakeCategory.WORD_ORDER -> "어순"
    MistakeCategory.VOCABULARY -> "어휘 선택"
    MistakeCategory.NATURALNESS -> "자연스러움"
    MistakeCategory.SPELLING -> "철자"
    MistakeCategory.OTHER -> "기타"
}

private fun advice(c: MistakeCategory): String = when (c) {
    MistakeCategory.ARTICLE ->
        "관사가 가장 많습니다. 한국어에 없는 범주라 규칙 암기보다, 읽을 때 명사 앞을 의식적으로 보는 편이 빠릅니다."
    MistakeCategory.TENSE ->
        "시제가 가장 많습니다. 특히 완료형과 과거의 구분을 의도적으로 연습해보세요."
    MistakeCategory.PREPOSITION ->
        "전치사가 가장 많습니다. 개별 암기보다 동사와 묶인 덩어리로 외우는 편이 남습니다."
    MistakeCategory.WORD_ORDER ->
        "어순이 가장 많습니다. 한국어 어순을 그대로 옮기고 있을 가능성이 큽니다."
    MistakeCategory.NATURALNESS ->
        "문법은 맞는데 원어민이 쓰지 않는 표현이 많습니다. 듣기에서 모은 문장을 그대로 빌려 쓰는 연습이 직접적입니다."
    MistakeCategory.VOCABULARY ->
        "단어 선택이 가장 많습니다. 비슷한 뜻의 단어들이 실제로 어떤 맥락에서 쓰이는지 확인해보세요."
    else -> "가장 자주 틀리는 유형부터 하나씩 줄여보세요."
}

private val DATE = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
