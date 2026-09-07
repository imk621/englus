package com.englus.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One connection to [PlaybackService], shared by everything in the app.
 *
 * Held process-wide rather than per screen for two reasons. Connecting takes a
 * round trip, and doing it again on every navigation would put a gap at the
 * start of playback. More importantly, the short-clip screens - dictation,
 * shadowing, drills - need a way to silence the episode before they play their
 * own audio, and they can only do that if they can reach the same controller.
 */
object PlaybackConnection {

    @Volatile private var controller: MediaController? = null
    private var connecting: Boolean = false

    /** The controller once connected; null while it is still being established. */
    val player: Player? get() = controller

    suspend fun await(context: Context): MediaController {
        controller?.let { if (it.isConnected) return it }
        return connect(context.applicationContext)
    }

    private suspend fun connect(context: Context): MediaController =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    val result = runCatching { future.get() }.getOrNull()
                    if (result != null) {
                        controller = result
                        cont.resume(result)
                    } else {
                        cont.cancel()
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    /**
     * Silences background listening before a screen plays its own clip.
     *
     * Two players in one app do not negotiate with each other - audio focus is
     * granted per app, so both would simply talk at once. Whoever starts a clip
     * has to stop the episode explicitly.
     */
    fun pause() {
        controller?.let { if (it.isConnected && it.isPlaying) it.pause() }
    }
}
