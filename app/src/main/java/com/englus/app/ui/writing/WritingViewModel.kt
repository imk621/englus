package com.englus.app.ui.writing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.MistakeCount
import com.englus.app.data.Writing
import com.englus.app.data.WritingMistake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WritingUi(
    val prompt: String = "",
    val text: String = "",
    val busy: String? = null,
    val error: String? = null,
    /** The writing just corrected, shown expanded below the editor. */
    val resultId: Long? = null,
    val expandedId: Long? = null,
)

class WritingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).writing

    private val _ui = MutableStateFlow(WritingUi())
    val ui: StateFlow<WritingUi> = _ui.asStateFlow()

    val configured: Boolean get() = repo.configured

    val writings: StateFlow<List<Writing>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mistakeCounts: StateFlow<List<MistakeCount>> =
        repo.observeMistakeCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val mistakes: StateFlow<List<WritingMistake>> =
        _ui.map { it.expandedId ?: it.resultId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeMistakes(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPrompt(v: String) = _ui.update { it.copy(prompt = v) }
    fun setText(v: String) = _ui.update { it.copy(text = v, error = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    fun toggleExpanded(id: Long) = _ui.update {
        it.copy(expandedId = if (it.expandedId == id) null else id, resultId = null)
    }

    fun suggestPrompt() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = "주제 받는 중…", error = null) }
            try {
                val p = repo.suggestPrompt()
                _ui.update { it.copy(busy = null, prompt = p) }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun correct() {
        val text = _ui.value.text.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "첨삭 중…", error = null, resultId = null) }
            try {
                val id = repo.correct(_ui.value.prompt.trim(), text)
                // The editor is cleared: the correction is saved and shown below,
                // and leaving the original text in the box invites correcting the
                // same paragraph over and over instead of writing something new.
                _ui.update {
                    it.copy(busy = null, resultId = id, expandedId = null, text = "", prompt = "")
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun delete(writing: Writing) {
        viewModelScope.launch { repo.delete(writing.id) }
    }
}
