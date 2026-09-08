package com.englus.app.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englus.app.EnglusApp
import com.englus.app.gemini.GeminiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.englus.app.backup.BackupRepository
import com.englus.app.backup.BackupInfo
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.material3.Switch
import com.englus.app.widget.SentenceWidget
import com.englus.app.exposure.ExposureSettings
import com.englus.app.data.feedsForLevel
import com.englus.app.level.LevelStore
import com.englus.app.material.MaterialWorker
import com.englus.app.material.MaterialSettings

data class MaterialUi(
    val enabled: Boolean = false,
    val perDay: Int = 1,
    val lastFetchAt: Long = 0,
    val level: String? = null,
    val feedCount: Int = 0,
)

data class ExposureUi(
    val enabled: Boolean = true,
    val notifySentences: Boolean = true,
    val widgetSentences: Boolean = true,
    val generatedSentences: Boolean = true,
    val quietFrom: Int = 22,
    val quietTo: Int = 8,
    val snoozed: Boolean = false,
    val status: String = "",
)

data class BackupUi(
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** Set once a file has been inspected and is waiting for confirmation. */
    val pending: BackupInfo? = null,
    val pendingUri: android.net.Uri? = null,
    val restored: Boolean = false,
)

data class SettingsUi(
    val apiKey: String = "",
    val model: String = GeminiSettings.DEFAULT_MODEL,
    val liveModel: String = GeminiSettings.DEFAULT_LIVE_MODEL,
    val models: List<String> = emptyList(),
    val savedKey: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val backup: BackupUi = BackupUi(),
    val exposure: ExposureUi = ExposureUi(),
    val material: MaterialUi = MaterialUi(),
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val explain = (app as EnglusApp).explain
    private val backups = BackupRepository(app)
    private val exposure = ExposureSettings(app)
    private val material = MaterialSettings(app)
    private val level = LevelStore(app)

    private val _ui = MutableStateFlow(
        SettingsUi(
            apiKey = explain.settings.apiKey,
            model = explain.settings.model,
            liveModel = explain.settings.liveModel,
            savedKey = explain.settings.configured,
        )
    )
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    fun setKey(v: String) = _ui.update { it.copy(apiKey = v, message = null, error = null) }

    fun save() {
        explain.settings.apiKey = _ui.value.apiKey
        _ui.update { it.copy(savedKey = explain.settings.configured, message = "저장했습니다.") }
    }

    fun clearKey() {
        explain.settings.apiKey = ""
        _ui.update { it.copy(apiKey = "", savedKey = false, models = emptyList(), message = "키를 삭제했습니다.") }
    }

    fun selectModel(m: String) {
        explain.settings.model = m
        _ui.update { it.copy(model = m, message = "모델을 $m 으로 설정했습니다.") }
    }

    /**
     * Re-read whenever the screen appears: quiet hours and a snooze both expire
     * on their own, so a snapshot goes stale while the screen is open.
     */
    fun refreshExposure() = _ui.update {
        it.copy(
            exposure = ExposureUi(
                enabled = exposure.enabled,
                notifySentences = exposure.notifySentences,
                widgetSentences = exposure.widgetSentences,
                generatedSentences = exposure.generatedSentences,
                quietFrom = exposure.quietFromHour,
                quietTo = exposure.quietToHour,
                snoozed = exposure.snoozed,
                status = exposure.status(),
            )
        )
    }

    fun refreshMaterial() = _ui.update {
        val cefr = level.cefr?.label
        it.copy(
            material = MaterialUi(
                enabled = material.enabled,
                perDay = material.perDay,
                lastFetchAt = material.lastFetchAt,
                level = cefr,
                feedCount = feedsForLevel(cefr).size,
            )
        )
    }

    fun setMaterialEnabled(v: Boolean) {
        material.enabled = v
        if (v) MaterialWorker.schedule(getApplication()) else MaterialWorker.cancel(getApplication())
        refreshMaterial()
    }

    fun setMaterialPerDay(v: Int) {
        material.perDay = v
        refreshMaterial()
    }

    fun setExposureEnabled(v: Boolean) {
        exposure.enabled = v
        applyExposure()
    }

    fun setNotifySentences(v: Boolean) {
        exposure.notifySentences = v
        applyExposure()
    }

    fun setWidgetSentences(v: Boolean) {
        exposure.widgetSentences = v
        applyExposure()
    }

    fun setGeneratedSentences(v: Boolean) {
        exposure.generatedSentences = v
        applyExposure()
    }

    fun setQuietHours(from: Int, to: Int) {
        exposure.quietFromHour = from
        exposure.quietToHour = to
        applyExposure()
    }

    fun snoozeForToday() {
        exposure.snoozeForToday()
        applyExposure()
    }

    fun resumeExposure() {
        exposure.resume()
        applyExposure()
    }

    /** The widget is pushed immediately: the value of an off switch is that it is now. */
    private fun applyExposure() {
        SentenceWidget.refresh(getApplication())
        refreshExposure()
    }

    fun suggestedBackupName(): String =
        BackupRepository.suggestedFileName(System.currentTimeMillis())

    fun exportTo(target: android.net.Uri) {
        setBackup { it.copy(busy = "백업 파일 만드는 중…", message = null, error = null) }
        viewModelScope.launch {
            try {
                val info = backups.export(target)
                setBackup {
                    it.copy(
                        busy = null,
                        message = "저장했습니다 · 저장 문장 ${info.savedSentences}개, " +
                            "복습 기록 ${info.reviewLogs}건, 단어 ${info.vocabWords}개",
                    )
                }
            } catch (e: Throwable) {
                setBackup { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Reads the file before offering to use it.
     *
     * Restoring replaces everything, so the learner should see what is in the
     * file - and how old it is - before that happens, not afterwards.
     */
    fun inspect(source: android.net.Uri) {
        setBackup { it.copy(busy = "백업 파일 읽는 중…", message = null, error = null) }
        viewModelScope.launch {
            try {
                val info = backups.inspect(source)
                setBackup { it.copy(busy = null, pending = info, pendingUri = source) }
            } catch (e: Throwable) {
                setBackup { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancelRestore() = setBackup { it.copy(pending = null, pendingUri = null) }

    fun confirmRestore() {
        val uri = _ui.value.backup.pendingUri ?: return
        setBackup { it.copy(busy = "복원 중…", pending = null, pendingUri = null) }
        viewModelScope.launch {
            try {
                backups.restore(uri)
                val cleared = backups.reconcileMissingAudio()
                setBackup {
                    it.copy(
                        busy = null,
                        restored = true,
                        message = "복원했습니다." + if (cleared > 0) {
                            " 오디오가 없는 에피소드 ${cleared}개는 글로만 복습합니다."
                        } else "",
                    )
                }
            } catch (e: Throwable) {
                setBackup { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun dismissBackupMessage() = setBackup { it.copy(message = null, error = null) }

    private fun setBackup(block: (BackupUi) -> BackupUi) =
        _ui.update { it.copy(backup = block(it.backup)) }

    fun setLiveModel(v: String) {
        explain.settings.liveModel = v
        _ui.update { it.copy(liveModel = v) }
    }

    /**
     * Fetching the list doubles as a key check: a bad key fails here with the
     * API's own message instead of surfacing later as a failed explanation.
     */
    fun loadModels() {
        explain.settings.apiKey = _ui.value.apiKey
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, message = null) }
            try {
                val models = explain.listModels()
                _ui.update {
                    it.copy(busy = false, models = models, message = "키가 유효합니다 (${models.size}개 모델)")
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = false, error = e.message ?: e.toString()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Gemini API 키", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (ui.savedKey) "설정됨" else "미설정",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (ui.savedKey) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "문장 설명, 영작 첨삭, 듣기 시험, 실시간 회화에 쓰입니다. " +
                                "전사·해석·받아쓰기·복습·어휘는 전부 기기 안에서 처리되므로, " +
                                "키가 없어도 앱의 중심 기능은 그대로 동작합니다. " +
                                "이 중 실제로 요금이 나가는 것은 실시간 회화뿐입니다.",
                            fontSize = 12.sp,
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("발급 방법", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "1. 아래 버튼으로 Google AI Studio 를 엽니다\n" +
                                "2. Google 계정으로 로그인합니다\n" +
                                "3. Create API key 를 눌러 키를 만듭니다\n" +
                                "4. 키를 복사해 아래 칸에 붙여넣고 저장합니다",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL))
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("키 발급받기 (aistudio.google.com)") }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "무료입니다. Gemini 앱 구독과는 별개이고, 구독만으로는 API 를 쓸 수 없습니다.\n\n" +
                                "키는 이 기기에만 저장됩니다. 여러 사람이 쓴다면 각자 자기 키를 " +
                                "발급받으세요 — 한 키를 나눠 쓰면 하루 사용량을 함께 소진하고, " +
                                "누가 얼마나 썼는지도 구분되지 않습니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = ui.apiKey,
                    onValueChange = vm::setKey,
                    label = { Text("API 키") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.save() },
                        enabled = ui.apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("저장") }
                    OutlinedButton(
                        onClick = { vm.loadModels() },
                        enabled = ui.apiKey.isNotBlank() && !ui.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (ui.busy) "확인 중…" else "키 확인 · 모델 목록") }
                }
                if (ui.savedKey) {
                    TextButton(onClick = { vm.clearKey() }) {
                        Text("키 삭제", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            ui.message?.let { item { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) } }
            ui.error?.let {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) { Text(it, Modifier.padding(12.dp), fontSize = 12.sp) }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("모델", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "현재: ${ui.model}\n" +
                        "모델 이름은 몇 주 단위로 바뀌고 구버전은 종료됩니다. " +
                        "'자세히'가 모델 오류를 내면 여기서 목록을 새로 받아 바꾸세요.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(ui.models.filter { it.contains("flash") || it.contains("pro") }) { m ->
                FilterChip(
                    selected = m == ui.model,
                    onClick = { vm.selectModel(m) },
                    label = { Text(m, fontSize = 12.sp) },
                )
            }

            item { MaterialSection(ui.material, vm) }

            item { ExposureSection(ui.exposure, vm) }

            item { BackupSection(ui.backup, vm) }

            item {
                Spacer(Modifier.height(10.dp))
                Text("실시간 회화 모델", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                // Typed rather than chosen from a list: the models endpoint above
                // reports what supports generateContent, and the Live models do
                // not appear there. Their ids are previews and get renamed, so
                // this has to be editable rather than a constant in the code.
                Text(
                    "실시간 회화는 별도 모델을 씁니다. 위 목록에는 나오지 않습니다 - " +
                        "그 목록은 텍스트 생성을 지원하는 모델만 보여주기 때문입니다. " +
                        "프리뷰 이름이라 바뀌면 여기서 고치세요.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.liveModel,
                    onValueChange = vm::setLiveModel,
                    label = { Text("Live 모델") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private const val AI_STUDIO_URL = "https://aistudio.google.com/app/apikey"


/**
 * The only way back from a lost phone.
 *
 * Placed in settings rather than behind a menu because a backup nobody knows
 * about is a backup nobody makes. What it does and does not carry is stated on
 * the screen: discovering after a restore that the API key is not in the file
 * is a small annoyance, but believing the audio was in there is not.
 */
@Composable
private fun BackupSection(backup: BackupUi, vm: SettingsViewModel) {
    val context = LocalContext.current

    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(vm::exportTo) }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::inspect) }

    Column(Modifier.padding(top = 10.dp)) {
        Text("백업", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "복습 일정, 어휘 프로필, 저장한 문장, 받아쓰기 기록을 파일 하나로 " +
                "내보냅니다. 이 앱은 서버도 계정도 없어서, 폰을 잃으면 되돌릴 " +
                "방법이 이것뿐입니다.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "오디오와 API 키는 들어가지 않습니다. 오디오는 다시 받을 수 있고 " +
                "백업이 감당 못 할 크기이며, 키는 파일에 평문으로 남기면 " +
                "백업이 곧 유출입니다.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        backup.busy?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { create.launch(vm.suggestedBackupName()) },
                enabled = backup.busy == null && !backup.restored,
                modifier = Modifier.weight(1f),
            ) { Text("백업 파일 만들기", fontSize = 13.sp) }

            OutlinedButton(
                onClick = { open.launch(arrayOf("application/zip", "*/*")) },
                enabled = backup.busy == null && !backup.restored,
                modifier = Modifier.weight(1f),
            ) { Text("복원", fontSize = 13.sp) }
        }

        backup.message?.let {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) { Text(it, Modifier.padding(12.dp), fontSize = 12.sp) }
        }
        backup.error?.let {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) { Text(it, Modifier.padding(12.dp), fontSize = 12.sp) }
        }

        // Restoring swapped the database under a running process. Carrying on
        // with the old one loaded would show stale data and write it back.
        if (backup.restored) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("앱 다시 시작") }
        }
    }

    backup.pending?.let { info ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("이 백업으로 되돌릴까요?") },
            text = {
                Column {
                    Text(
                        java.text.SimpleDateFormat("yyyy년 M월 d일 HH:mm", java.util.Locale.KOREA)
                            .format(java.util.Date(info.createdAt)),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Two calls rather than one string with an escape: this file
                    // has had a newline escape turn into a real line break twice.
                    Text(
                        "에피소드 ${info.episodes}개 · 저장 문장 ${info.savedSentences}개",
                        fontSize = 13.sp,
                    )
                    Text(
                        "복습 기록 ${info.reviewLogs}건 · 단어 ${info.vocabWords}개",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "지금 이 기기에 있는 학습 기록은 전부 사라지고 이 파일의 것으로 " +
                            "바뀝니다. 되돌릴 수 없습니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRestore) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelRestore) { Text("취소") }
            },
        )
    }
}

/**
 * Turning the ambient features off.
 *
 * Placed above backup because it is the control people reach for in a hurry -
 * in a meeting, at dinner, with a sleeping child in the room. Three levels,
 * because "not now" and "not ever" are different requests and offering only the
 * second means the feature gets switched off once and never returns.
 */
@Composable
private fun ExposureSection(exposure: ExposureUi, vm: SettingsViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshExposure() }

    Column(Modifier.padding(top = 10.dp)) {
        Text("영어 노출", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "홈 화면 위젯과 알림이 먼저 말을 거는 기능입니다. 재생과 핸즈프리는 " +
                "직접 눌러야 시작하므로 여기서 다루지 않습니다 - 멈추려면 일시정지를 " +
                "누르면 됩니다.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("먼저 말 걸기", fontSize = 14.sp)
                Text(
                    exposure.status(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = exposure.enabled, onCheckedChange = vm::setExposureEnabled)
        }

        if (exposure.enabled) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exposure.snoozed) {
                    OutlinedButton(onClick = vm::resumeExposure, modifier = Modifier.weight(1f)) {
                        Text("지금 다시 켜기", fontSize = 13.sp)
                    }
                } else {
                    OutlinedButton(onClick = vm::snoozeForToday, modifier = Modifier.weight(1f)) {
                        Text("오늘은 그만", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("알림에 문장 띄우기", Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = exposure.notifySentences, onCheckedChange = vm::setNotifySentences)
            }
            Text(
                "끄면 '복습할 문장 N개'만 뜹니다. 켜면 알림을 보는 순간이 곧 한 번 " +
                    "읽는 것이 됩니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("위젯에 문장 띄우기", Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = exposure.widgetSentences, onCheckedChange = vm::setWidgetSentences)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("생성 문장도 섞기", Modifier.weight(1f), fontSize = 13.sp)
                Switch(
                    checked = exposure.generatedSentences,
                    onCheckedChange = vm::setGeneratedSentences,
                )
            }
            Text(
                "오늘의 문장을 위젯과 알림에도 띄웁니다. 나머지는 실제로 놓친 문장이라 " +
                    "뒤에 오디오가 있지만 이건 없습니다. 그래서 화면에 '생성'이라고 " +
                    "표시되고, 이 스위치로 따로 끌 수 있습니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text("방해 금지 시간", fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "이 시간에는 위젯도 알림도 조용합니다. 자는 동안 폰이 영어로 " +
                    "켜지는 일은 없어야 합니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("없음", 0, 0),
                    Triple("22-8시", 22, 8),
                    Triple("23-7시", 23, 7),
                    Triple("21-9시", 21, 9),
                ).forEach { (label, from, to) ->
                    FilterChip(
                        selected = exposure.quietFrom == from && exposure.quietTo == to,
                        onClick = { vm.setQuietHours(from, to) },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
        }
    }
}

private fun ExposureUi.status(): String = status

/**
 * Letting the app go and find material by itself.
 *
 * Before this, every session began with the learner deciding what to listen to
 * and then waiting for it - subscribe, refresh, pick, download, transcribe. A
 * habit does not survive five steps standing in front of it.
 */
@Composable
private fun MaterialSection(material: MaterialUi, vm: SettingsViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshMaterial() }

    Column(Modifier.padding(top = 10.dp)) {
        Text("자동 자료 수급", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "하루에 한 편씩, 내 수준에 맞는 무료 팟캐스트에서 새 에피소드를 " +
                "무작위로 받아 전사까지 마쳐 둡니다. 같은 자료만 돌면 입력이 아니라 " +
                "가구가 되기 때문에 무작위입니다.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("자동으로 받기", fontSize = 14.sp)
                Text(
                    if (material.level == null) {
                        "수준을 아직 재지 않아 초급·중급에서 받습니다"
                    } else {
                        "${material.level} 기준 · 대상 피드 ${material.feedCount}개"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = material.enabled, onCheckedChange = vm::setMaterialEnabled)
        }

        if (material.enabled) {
            Spacer(Modifier.height(10.dp))
            Text("하루에", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, 3).forEach { n ->
                    FilterChip(
                        selected = material.perDay == n,
                        onClick = { vm.setMaterialPerDay(n) },
                        label = { Text("${n}편", fontSize = 11.sp) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Said plainly, because these are the learner's resources being spent.
            Text(
                "Wi-Fi 에 연결되고 충전 중일 때만 받습니다. 한 편이 수십 MB 이고 " +
                    "전사에 몇 분간 CPU 를 쓰기 때문에, 데이터나 배터리로 할 일이 " +
                    "아닙니다. 어차피 자료는 내일 들을 것입니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (material.lastFetchAt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "마지막 수급: " + java.text.SimpleDateFormat(
                        "M월 d일 HH:mm", java.util.Locale.KOREA
                    ).format(java.util.Date(material.lastFetchAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
