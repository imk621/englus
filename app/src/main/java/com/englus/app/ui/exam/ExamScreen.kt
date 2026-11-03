package com.englus.app.ui.exam

import android.app.Application
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.englus.app.EnglusApp
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.ExamKind
import com.englus.app.data.ExamQuestion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ExamUi(
    val generating: Boolean = true,
    val error: String? = null,
    val sessionId: Long = 0,
    val index: Int = 0,
    val chosen: Int = -1,
    val correct: Int = 0,
    val elapsedMs: Long = 0,
    val finished: Boolean = false,
)

class ExamViewModel(app: Application, private val episodeId: Long) : AndroidViewModel(app) {

    private val exams = (app as EnglusApp).exams
    private val episodeDao = EnglusDatabase.get(app).episodes()

    private val syncOffsetMs =
        app.getSharedPreferences("player", Application.MODE_PRIVATE).getInt("syncOffsetMs", 0)

    private val _ui = MutableStateFlow(ExamUi())
    val ui: StateFlow<ExamUi> = _ui.asStateFlow()

    private val _questions = MutableStateFlow<List<ExamQuestion>>(emptyList())
    val questions: StateFlow<List<ExamQuestion>> = _questions.asStateFlow()

    private val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private var clipEndMs = Long.MAX_VALUE
    private var startedAt = 0L

    init {
        viewModelScope.launch {
            try {
                val episode = episodeDao.get(episodeId)
                if (episode != null && episode.audioPath.isNotBlank()) {
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(episode.audioPath))))
                    player.prepare()
                }
                val id = exams.generate(episodeId)
                startedAt = System.currentTimeMillis()
                _ui.update { it.copy(generating = false, sessionId = id) }

                exams.observeQuestions(id).collect { list ->
                    _questions.value = list
                    if (list.isNotEmpty() && _ui.value.index == 0 && _ui.value.chosen < 0) playCurrent()
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(generating = false, error = e.message ?: e.toString()) }
            }
        }
        viewModelScope.launch {
            while (true) {
                if (player.isPlaying && player.currentPosition >= clipEndMs) player.pause()
                if (!_ui.value.generating && !_ui.value.finished && startedAt > 0) {
                    _ui.update { it.copy(elapsedMs = System.currentTimeMillis() - startedAt) }
                }
                delay(200)
            }
        }
    }

    private fun currentQuestion(): ExamQuestion? = _questions.value.getOrNull(_ui.value.index)

    fun playCurrent() {
        val q = currentQuestion() ?: return
        if (q.endMs <= 0) return
        clipEndMs = q.endMs + syncOffsetMs + TAIL_PAD_MS
        player.seekTo((q.startMs + syncOffsetMs - HEAD_PAD_MS).coerceAtLeast(0))
        player.play()
    }

    fun choose(index: Int) {
        val q = currentQuestion() ?: return
        if (_ui.value.chosen >= 0) return
        player.pause()
        _ui.update {
            it.copy(chosen = index, correct = it.correct + if (index == q.correctIndex) 1 else 0)
        }
        viewModelScope.launch { exams.answer(q, index) }
    }

    fun next() {
        val nextIndex = _ui.value.index + 1
        if (nextIndex > _questions.value.lastIndex) {
            player.pause()
            val ui = _ui.value
            viewModelScope.launch { exams.finish(ui.sessionId, ui.correct, ui.elapsedMs) }
            _ui.update { it.copy(finished = true) }
            return
        }
        _ui.update { it.copy(index = nextIndex, chosen = -1) }
        playCurrent()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val HEAD_PAD_MS = 150
        const val TAIL_PAD_MS = 350
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(vm: ExamViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val questions by vm.questions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (questions.isEmpty()) "듣기 시험"
                        else "듣기 시험  ${ui.index + 1} / ${questions.size}   ${formatClock(ui.elapsedMs)}"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                ui.generating -> {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        "문제를 만드는 중…",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "이 에피소드의 문장으로 4지선다를 만듭니다. 오답은 실제로 잘못 들릴 법한 " +
                            "형태로 생성됩니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }

                ui.error != null -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) { Text(ui.error!!, Modifier.padding(14.dp), fontSize = 13.sp) }
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                }

                ui.finished -> {
                    val pct = if (questions.isEmpty()) 0 else ui.correct * 100 / questions.size
                    Spacer(Modifier.height(30.dp))
                    Text(
                        "${pct}점",
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${questions.size}문제 중 ${ui.correct}개 정답 · ${formatClock(ui.elapsedMs)}",
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The point of the exam is the wrong answers; each one names a
                    // clip that can be replayed.
                    questions.filter { !it.isCorrect }.forEach { q ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    q.options.getOrNull(q.correctIndex).orEmpty(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                if (q.chosenIndex >= 0) {
                                    Text(
                                        "내 답: ${q.options.getOrNull(q.chosenIndex).orEmpty()}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (q.explanation.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(q.explanation, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
                }

                questions.isNotEmpty() -> {
                    val q = questions[ui.index.coerceAtMost(questions.lastIndex)]
                    LinearProgressIndicator(
                        progress = { (ui.index + 1).toFloat() / questions.size },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (q.endMs > 0) {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                FilledIconButton(
                                    onClick = { vm.playCurrent() },
                                    modifier = Modifier.size(56.dp),
                                ) { Icon(Icons.Default.Replay, "다시 듣기", Modifier.size(26.dp)) }
                            }
                        }
                    }

                    if (q.prompt.isNotBlank()) {
                        Text(q.prompt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("무엇이라고 말했습니까?", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    q.options.forEachIndexed { i, option ->
                        val answered = ui.chosen >= 0
                        val container = when {
                            !answered -> MaterialTheme.colorScheme.surfaceVariant
                            i == q.correctIndex -> Color(0xFF1B5E20)
                            i == ui.chosen -> Color(0xFFB3261E)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val content = if (answered && (i == q.correctIndex || i == ui.chosen))
                            Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                        Button(
                            onClick = { vm.choose(i) },
                            enabled = !answered,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = container,
                                disabledContainerColor = container,
                                contentColor = content,
                                disabledContentColor = content,
                            ),
                        ) {
                            Text(option, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (ui.chosen >= 0) {
                        if (q.explanation.isNotBlank()) {
                            Card { Text(q.explanation, Modifier.padding(14.dp), fontSize = 13.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (q.endMs > 0) {
                                OutlinedButton(
                                    onClick = { vm.playCurrent() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("다시 듣기") }
                            }
                            Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) {
                                Text(if (ui.index == questions.lastIndex) "결과 보기" else "다음")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Kept here so the kind breakdown has a label without another file. */
fun examKindLabel(kind: ExamKind): String = when (kind) {
    ExamKind.HEARD -> "들은 대로"
    ExamKind.MEANING -> "내용 이해"
}
