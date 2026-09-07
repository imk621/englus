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
import com.englus.app.data.SavedSentence
import com.englus.app.playback.HandsFree
import com.englus.app.playback.HandsFreeClip
import com.englus.app.playback.PlaybackConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Saved sentences that still have audio to imitate. */
    val missedCount: Int = 0,
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
        db.dictation().observeSaved(),
    ) { episodes, progress, count, average, saved ->
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
            // A saved sentence with no audio, or no timing, has nothing to
            // imitate - counting it would promise a queue that opens empty.
            missedCount = saved.count {
                it.audioPath.isNotBlank() && it.sentence.endMs > it.sentence.startMs
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpeakingHomeUi())

    /**
     * Loads the missed sentences into the media session and starts them.
     *
     * Handed to the session rather than played here so that it survives the
     * screen being switched off, which is the entire point: this is meant to be
     * started and then put in a pocket.
     */
    fun startHandsFree() {
        viewModelScope.launch {
            val clips = db.dictation().observeSaved().first()
                .filter { it.audioPath.isNotBlank() && it.sentence.endMs > it.sentence.startMs }
                .map { it.toClip() }
            if (clips.isEmpty()) return@launch

            val controller = PlaybackConnection.await(getApplication())
            controller.setMediaItems(HandsFree.buildQueue(getApplication(), clips))
            controller.prepare()
            controller.play()
        }
    }

    private fun SavedSentence.toClip() = HandsFreeClip(
        text = sentence.text,
        audioPath = audioPath,
        startMs = sentence.startMs,
        endMs = sentence.endMs,
        source = episodeTitle,
    )
}
