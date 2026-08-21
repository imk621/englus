package com.englus.app.ui.speaking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englus.app.EnglusApp
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Episode
import com.englus.app.data.EpisodeKind
import com.englus.app.data.TranscriptState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One episode that can be shadowed, with how far the learner has got in it. */
data class ShadowTarget(
    val episode: Episode,
    val attempts: Int,
    val average: Float?,
)

data class SpeakingHomeUi(
    val targets: List<ShadowTarget> = emptyList(),
    val totalAttempts: Int = 0,
    val overallAverage: Float? = null,
    val modelReady: Boolean = true,
    val liveConfigured: Boolean = false,
    val liveEnabled: Boolean = false,
)

/**
 * The speaking tab.
 *
 * Shadowing is the free half and it is the half that runs today, so it is what
 * this screen is made of. Live conversation sits underneath as an option,
 * because it costs money per minute and a learner who does not want to spend any
 * should not open this tab and be told that speaking practice requires a card.
 */
class SpeakingHomeViewModel(app: Application) : AndroidViewModel(app) {

    private val db = EnglusDatabase.get(app)
    private val models = (app as EnglusApp).models
    private val live = (app as EnglusApp).live

    val ui: StateFlow<SpeakingHomeUi> = combine(
        db.episodes().observeAll(),
        db.speaking().observeProgress(),
        db.speaking().observeCount(),
        db.speaking().observeOverallAverage(),
    ) { episodes, progress, count, average ->
        val byEpisode = progress.associateBy { it.episodeId }
        SpeakingHomeUi(
            // Shadowing needs audio to imitate, so reading material is not a
            // candidate however good the text is.
            targets = episodes
                .filter { it.kind == EpisodeKind.LISTENING && it.state == TranscriptState.DONE }
                .map { e ->
                    val p = byEpisode[e.id]
                    ShadowTarget(e, p?.attempts ?: 0, p?.average)
                },
            totalAttempts = count,
            overallAverage = average,
            modelReady = models.available().isNotEmpty(),
            liveConfigured = live.configured,
            liveEnabled = live.budget.dailyMinutes > 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpeakingHomeUi())
}
