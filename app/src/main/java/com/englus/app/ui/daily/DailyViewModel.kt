package com.englus.app.ui.daily

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.DailySentence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DailyUi(
    val busy: Boolean = false,
    val error: String? = null,
    val configured: Boolean = false,
)

/**
 * Today's generated set, shared by the writing and speaking entry points.
 *
 * One ViewModel rather than one per screen so that opening the speaking task
 * after the writing task does not fire a second generation for the same day.
 */
class DailyViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as EnglusApp).daily

    val today: StateFlow<List<DailySentence>> = repo.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(DailyUi(configured = repo.configured))
    val ui: StateFlow<DailyUi> = _ui.asStateFlow()

    fun refresh() = _ui.update { it.copy(configured = repo.configured) }

    fun generate(force: Boolean = false) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.generateToday(force)
                _ui.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /**
     * Marks the task as taken up.
     *
     * Done when the prompt is handed to the writing screen rather than when the
     * correction comes back: the learner has moved on to it either way, and a
     * card that keeps offering the same sentence after they started it is worse
     * than one that advances a little early.
     */
    fun markWritten(id: Long) {
        viewModelScope.launch { repo.markWritten(id) }
    }

    /** The first one not yet written, so the card advances as work is done. */
    fun nextForWriting(): DailySentence? = today.value.firstOrNull { it.writtenAt == null }

    fun nextForSpeaking(): DailySentence? = today.value.firstOrNull { it.spokenAt == null }
}
