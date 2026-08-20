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

data class SettingsUi(
    val apiKey: String = "",
    val model: String = GeminiSettings.DEFAULT_MODEL,
    val models: List<String> = emptyList(),
    val savedKey: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val explain = (app as EnglusApp).explain

    private val _ui = MutableStateFlow(
        SettingsUi(
            apiKey = explain.settings.apiKey,
            model = explain.settings.model,
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
                            "'자세히' 설명에만 쓰입니다. 전사·해석·받아쓰기는 전부 기기 안에서 " +
                                "처리되므로 키 없이도 앱은 완전히 동작합니다.",
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
        }
    }
}

private const val AI_STUDIO_URL = "https://aistudio.google.com/app/apikey"
