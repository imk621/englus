package com.englus.app.ui.vocab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Episode
import com.englus.app.data.TranscriptState
import com.englus.app.data.VocabBandStat
import com.englus.app.data.VocabWord
import com.englus.app.vocab.UnknownRate
import com.englus.app.vocab.VocabRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One piece of material weighed against this learner's profile. */
data class Recommendation(
    val episode: Episode,
    val unknown: UnknownRate,
)

data class VocabUi(
    val met: Int = 0,
    val known: Int = 0,
    val bands: List<VocabBandStat> = emptyList(),
    val estimate: Int? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val scanning: Boolean = false,
    /** True when the library held more episodes than [MAX_SCANNED]. */
    val truncated: Boolean = false,
)

class VocabViewModel(app: Application) : AndroidViewModel(app) {

    private val vocab: VocabRepository = (app as EnglusApp).vocab
    private val episodes = EnglusDatabase.get(app).episodes()

    val unknownWords: StateFlow<List<VocabWord>> =
        vocab.observeUnknown().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(VocabUi())
    val ui: StateFlow<VocabUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                vocab.observeMetCount(),
                vocab.observeKnownCount(),
                vocab.observeBandStats(),
            ) { met, known, bands -> Triple(met, known, bands) }
                .collect { (met, known, bands) ->
                    _ui.update {
                        it.copy(
                            met = met,
                            known = known,
                            bands = bands,
                            estimate = VocabRepository.estimateSize(bands),
                        )
                    }
                }
        }
    }

    fun markKnown(lemma: String, known: Boolean) {
        viewModelScope.launch { vocab.setKnown(lemma, known) }
    }

    /**
     * Rates the library against the profile.
     *
     * Explicit rather than automatic: this reads every sentence of every
     * episode, and doing it on screen open would stall the list for a library
     * that has been used for a while.
     */
    fun scanLibrary() {
        if (_ui.value.scanning) return
        _ui.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val all = episodes.observeAll().first().filter { it.state == TranscriptState.DONE }
            val scanned = all.take(MAX_SCANNED)
            val results = scanned.mapNotNull { episode ->
                val sentences = episodes.observeSentences(episode.id).first().map { it.text }
                if (sentences.isEmpty()) return@mapNotNull null
                val rate = vocab.unknownRate(sentences)
                if (rate.totalTokens < VocabRepository.MIN_TOKENS_FOR_ESTIMATE) null
                else Recommendation(episode, rate)
            }
            _ui.update {
                it.copy(
                    scanning = false,
                    truncated = all.size > scanned.size,
                    // Closest to the middle of the useful band first: material at
                    // 2-5% unknown teaches without collapsing comprehension.
                    recommendations = results.sortedBy { r ->
                        kotlin.math.abs(r.unknown.rate - TARGET_RATE)
                    },
                )
            }
        }
    }

    private companion object {
        const val MAX_SCANNED = 30
        const val TARGET_RATE = 0.035f
    }
}
