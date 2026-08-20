package com.englus.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LibraryUi(
    val models: List<File> = emptyList(),
    val busy: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository
    private val modelStore = (app as EnglusApp).models

    val episodes: StateFlow<List<Episode>> =
        repo.observeEpisodes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()

    init { refreshModels() }

    fun refreshModels() = _ui.update { it.copy(models = modelStore.available()) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = "모델 복사 중…", progress = 0f, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    modelStore.import(uri) { p -> _ui.update { s -> s.copy(progress = p) } }
                }
                refreshModels()
                _ui.update { it.copy(busy = null) }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, error = "모델 가져오기 실패: ${e.message}") }
            }
        }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = "오디오 가져오는 중…", progress = 0f, error = null) }
            try {
                repo.import(uri)
                _ui.update { it.copy(busy = null) }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, error = "오디오 가져오기 실패: ${e.message}") }
            }
        }
    }

    fun retry(episode: Episode) = repo.enqueue(episode.id)

    fun delete(episode: Episode) {
        viewModelScope.launch { repo.delete(episode) }
    }
}
