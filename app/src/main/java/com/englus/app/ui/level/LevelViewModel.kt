package com.englus.app.ui.level

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.level.LevelItem
import com.englus.app.level.LevelResult
import com.englus.app.level.LevelStore
import com.englus.app.level.LevelTest
import com.englus.app.vocab.FrequencyList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LevelUi(
    val items: List<LevelItem> = emptyList(),
    val index: Int = 0,
    val claimed: Set<String> = emptySet(),
    val result: LevelResult? = null,
    val previousSize: Int? = null,
    val loading: Boolean = true,
) {
    val total: Int get() = items.size
    val current: LevelItem? get() = items.getOrNull(index)
}

class LevelViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LevelStore(app)

    private val _ui = MutableStateFlow(LevelUi())
    val ui: StateFlow<LevelUi> = _ui.asStateFlow()

    init { start() }

    fun start() {
        _ui.value = LevelUi(
            loading = true,
            previousSize = if (store.measured) store.vocabularySize else null,
        )
        viewModelScope.launch {
            val built = withContext(Dispatchers.IO) {
                val ranks = FrequencyList.get(getApplication())
                LevelTest.build(ranks, ranks.nonWords(getApplication()))
            }
            _ui.update { it.copy(items = built, loading = false) }
        }
    }

    /**
     * One answer, then straight on.
     *
     * No going back and no confirm step: the moment a learner starts weighing
     * whether they *really* know a word, they are answering a different question
     * than the one the scoring assumes.
     */
    fun answer(knows: Boolean) {
        val state = _ui.value
        val item = state.current ?: return
        val claimed = if (knows) state.claimed + item.word else state.claimed
        val next = state.index + 1

        if (next >= state.items.size) {
            val result = LevelTest.score(state.items, claimed, System.currentTimeMillis())
            store.save(result)
            _ui.update { it.copy(claimed = claimed, index = next, result = result) }
        } else {
            _ui.update { it.copy(claimed = claimed, index = next) }
        }
    }
}
