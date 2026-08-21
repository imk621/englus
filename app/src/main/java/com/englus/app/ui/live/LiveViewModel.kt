package com.englus.app.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.live.LiveEvent
import com.englus.app.live.LiveRepository
import com.englus.app.live.LiveSession
import com.englus.app.live.LiveTurn
import com.englus.app.live.Topic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LivePhase { PICKING, CONNECTING, TALKING, SAVED }

data class LiveUi(
    val phase: LivePhase = LivePhase.PICKING,
    val topics: List<Topic> = emptyList(),
    val topic: Topic? = null,
    val turns: List<LiveTurn> = emptyList(),
    /** The turn in progress, shown live so the learner can see they were heard. */
    val partialUser: String = "",
    val partialModel: String = "",
    val elapsedSec: Int = 0,
    val remainingSec: Int = 0,
    val micLevel: Float = 0f,
    val error: String? = null,
    val savedId: Long? = null,
    val reviewing: Boolean = false,
    val configured: Boolean = false,
    val dailyMinutes: Int = 0,
)

/**
 * Drives one live conversation.
 *
 * Most of the work here is turning a stream of transcription fragments back
 * into turns, and making sure the clock is charged for every second the socket
 * was actually open.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: LiveRepository = (app as EnglusApp).live

    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    val history = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var session: LiveSession? = null
    private var timer: Job? = null
    private var userBuffer = StringBuilder()
    private var modelBuffer = StringBuilder()

    init {
        refresh()
        viewModelScope.launch {
            val topics = repo.topicSuggestions()
            _ui.update { it.copy(topics = topics, topic = it.topic ?: topics.firstOrNull()) }
        }
    }

    /**
     * Re-read on resume.
     *
     * The key is entered on another screen, so a snapshot taken when this one
     * was first built keeps telling the learner to go and set a key they have
     * already set. The remaining budget is re-read for the same reason: it moves
     * at midnight and after a session started somewhere else.
     */
    fun refresh() = _ui.update {
        it.copy(
            configured = repo.configured,
            remainingSec = repo.budget.remainingSeconds,
            dailyMinutes = repo.budget.dailyMinutes,
        )
    }

    fun setTopic(topic: Topic) = _ui.update { it.copy(topic = topic) }

    fun setDailyMinutes(minutes: Int) {
        repo.budget.dailyMinutes = minutes
        _ui.update {
            it.copy(dailyMinutes = repo.budget.dailyMinutes, remainingSec = repo.budget.remainingSeconds)
        }
    }

    fun start() {
        if (session != null) return
        if (repo.budget.exhausted) {
            _ui.update { it.copy(error = "오늘 배정한 시간을 다 썼습니다.") }
            return
        }
        _ui.update { it.copy(phase = LivePhase.CONNECTING, error = null, turns = emptyList()) }

        viewModelScope.launch {
            try {
                val topic = _ui.value.topic ?: return@launch run {
                    _ui.update { it.copy(phase = LivePhase.PICKING, error = "주제를 고르세요.") }
                }
                val instruction = repo.systemInstruction(topic)
                val live = LiveSession(
                    context = getApplication(),
                    apiKey = repo.apiKey(),
                    model = repo.liveModel,
                    systemInstruction = instruction,
                )
                session = live
                viewModelScope.launch { live.events.collect(::onEvent) }
                viewModelScope.launch {
                    live.micLevel.collect { l -> _ui.update { it.copy(micLevel = l) } }
                }
                live.start()
            } catch (e: Throwable) {
                _ui.update { it.copy(phase = LivePhase.PICKING, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun onEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.Ready -> {
                _ui.update { it.copy(phase = LivePhase.TALKING) }
                startClock()
            }

            is LiveEvent.UserText -> {
                // The learner started: whatever the model was saying is a
                // finished turn now.
                flushModel()
                userBuffer.append(event.text)
                _ui.update { it.copy(partialUser = userBuffer.toString()) }
            }

            is LiveEvent.ModelText -> {
                flushUser()
                modelBuffer.append(event.text)
                _ui.update { it.copy(partialModel = modelBuffer.toString()) }
            }

            LiveEvent.TurnComplete -> {
                flushUser()
                flushModel()
            }

            LiveEvent.Interrupted -> flushModel()

            is LiveEvent.Failed -> {
                _ui.update { it.copy(error = event.message) }
                stop()
            }

            LiveEvent.Closed -> Unit
        }
    }

    private fun flushUser() {
        val text = userBuffer.toString().trim()
        userBuffer = StringBuilder()
        if (text.isEmpty()) return
        _ui.update { it.copy(turns = it.turns + LiveTurn(fromUser = true, text = text), partialUser = "") }
    }

    private fun flushModel() {
        val text = modelBuffer.toString().trim()
        modelBuffer = StringBuilder()
        if (text.isEmpty()) return
        _ui.update { it.copy(turns = it.turns + LiveTurn(fromUser = false, text = text), partialModel = "") }
    }

    /**
     * Charges the budget once a second while the socket is open.
     *
     * Not at the end: a session killed by the process dying or the network
     * dropping still cost money, and only settling up on a clean exit would make
     * the daily limit escapable by accident.
     */
    private fun startClock() {
        timer?.cancel()
        timer = viewModelScope.launch {
            while (true) {
                delay(1000)
                repo.budget.spend(1)
                val remaining = repo.budget.remainingSeconds
                _ui.update { it.copy(elapsedSec = it.elapsedSec + 1, remainingSec = remaining) }
                if (remaining <= 0) {
                    _ui.update { it.copy(error = "오늘 배정한 시간을 다 썼습니다.") }
                    stop()
                    return@launch
                }
            }
        }
    }

    /** Abandons a connection attempt without recording a conversation. */
    fun cancelConnecting() {
        session?.stop()
        session = null
        _ui.update { it.copy(phase = LivePhase.PICKING, error = null) }
    }

    fun stop() {
        timer?.cancel()
        timer = null
        session?.stop()
        session = null
        flushUser()
        flushModel()

        val state = _ui.value
        if (state.phase == LivePhase.PICKING) return
        if (state.turns.isEmpty()) {
            _ui.update { it.copy(phase = LivePhase.PICKING, elapsedSec = 0) }
            return
        }
        viewModelScope.launch {
            val id = repo.save(state.topic?.label.orEmpty(), state.turns, state.elapsedSec)
            _ui.update { it.copy(phase = LivePhase.SAVED, savedId = id) }
        }
    }

    /** The correction pass, on the cheap text model, after the call has ended. */
    fun review() {
        val id = _ui.value.savedId ?: return
        _ui.update { it.copy(reviewing = true, error = null) }
        viewModelScope.launch {
            try {
                repo.review(id)
                _ui.update { it.copy(reviewing = false) }
            } catch (e: Throwable) {
                _ui.update { it.copy(reviewing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun reset() {
        _ui.update {
            it.copy(
                phase = LivePhase.PICKING,
                turns = emptyList(),
                partialUser = "",
                partialModel = "",
                elapsedSec = 0,
                savedId = null,
                error = null,
                remainingSec = repo.budget.remainingSeconds,
            )
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        timer?.cancel()
        session?.stop()
        super.onCleared()
    }
}
