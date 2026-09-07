package com.englus.app.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * The one player that outlives the screen.
 *
 * Everything else in this app assumed a learner sitting in front of it, which
 * capped listening at whatever fits into a deliberate session. The hours that
 * are actually available - walking, commuting, washing up - are hands-busy and
 * eyes-busy, and a player owned by a ViewModel dies the moment the phone goes
 * into a pocket.
 *
 * A media session also buys the controls those hours need for free: lock screen,
 * notification, headphone buttons, and a car head unit.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // Pause for a phone call or another app's audio, and resume after.
                // Without this the episode keeps talking underneath everything.
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the app away should not silence something that is playing - that is
     * the pocket case this service exists for. It should, however, not leave a
     * paused service running for nothing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
