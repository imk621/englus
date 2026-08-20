package com.englus.app.ui.reading

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.Episode
import com.englus.app.data.Sentence
import com.englus.app.data.SentenceNote
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
import java.io.File

data class CaptureUi(
    val pasted: String = "",
    val busy: String? = null,
    val error: String? = null,
    val createdId: Long? = null,
)

class ReadingCaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val reading = (app as EnglusApp).reading

    private val _ui = MutableStateFlow(CaptureUi())
    val ui: StateFlow<CaptureUi> = _ui.asStateFlow()

    fun cameraFile(): File = reading.cameraFile()

    fun setPasted(v: String) = _ui.update { it.copy(pasted = v, error = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }
    fun consumeCreated() = _ui.update { it.copy(createdId = null) }

    fun importImage(uri: Uri) = run("글자 인식 중…") { reading.importImage(uri, null) }

    fun importPasted() {
        val text = _ui.value.pasted
        run("가져오는 중…") { reading.importText(text, null) }
    }

    private fun run(busy: String, block: suspend () -> Long) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = busy, error = null) }
            try {
                val id = block()
                _ui.update { it.copy(busy = null, createdId = id, pasted = "") }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }
}

data class ReaderUi(
    /** Per-sentence reveal, same discipline as the listening player. */
    val translated: Set<Long> = emptySet(),
    /** Escape hatch for reading a whole page at speed rather than studying it. */
    val showAll: Boolean = false,
    val detailSentenceId: Long? = null,
    val explaining: Boolean = false,
    val explainError: String? = null,
)

class ReaderViewModel(app: Application, private val episodeId: Long) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).repository
    private val explainRepo = (app as EnglusApp).explain

    val episode: StateFlow<Episode?> =
        repo.observeEpisode(episodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sentences: StateFlow<List<Sentence>> =
        repo.observeSentences(episodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(ReaderUi())
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()

    val explainConfigured: Boolean get() = explainRepo.configured

    @OptIn(ExperimentalCoroutinesApi::class)
    val note: StateFlow<SentenceNote?> =
        _ui.map { it.detailSentenceId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(null) else explainRepo.observeNote(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            repo.observeEpisode(episodeId).collect { ep ->
                if (ep != null && ep.translationState != com.englus.app.data.TranslationState.DONE) {
                    repo.enqueueTranslation(episodeId)
                }
            }
        }
    }

    fun toggleTranslation(id: Long) = _ui.update {
        it.copy(translated = if (id in it.translated) it.translated - id else it.translated + id)
    }

    fun toggleAllTranslations() = _ui.update { it.copy(showAll = !it.showAll) }

    fun toggleDetail(id: Long) = _ui.update {
        it.copy(detailSentenceId = if (it.detailSentenceId == id) null else id, explainError = null)
    }

    fun explain(sentence: Sentence) {
        _ui.update { it.copy(explaining = true, explainError = null) }
        viewModelScope.launch {
            try {
                explainRepo.explain(sentence, null)
                _ui.update { it.copy(explaining = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(explaining = false, explainError = e.message ?: e.toString()) }
            }
        }
    }

    fun toggleSaved(s: Sentence) {
        viewModelScope.launch { repo.setSaved(s.id, !s.saved) }
    }
}
