package com.englus.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

/** One sentence to shadow without looking at anything. */
data class HandsFreeClip(
    val text: String,
    val audioPath: String,
    val startMs: Long,
    val endMs: Long,
    val source: String,
)

/**
 * Shadowing with the phone in a pocket.
 *
 * Ordinary shadowing needs two hands and a screen: play, stop, record, read the
 * score, tap next. That confines speaking practice to the times a learner is
 * sitting down, which are the times they are least likely to spend on it. The
 * hours actually available for speaking out loud - walking, cooking, commuting
 * alone - are exactly the hours a screen cannot be used.
 *
 * So the queue is built as [sentence, silence, sentence, silence, ...] and handed
 * to the media session. The player, not the app, drives it, which means it keeps
 * going with the screen off, shows the sentence on the lock screen, and answers
 * the headphone button.
 *
 * There is no scoring here. Recording into wind and traffic and running whisper
 * over it would produce a number that measures the street, and a wrong score is
 * worse than no score. This mode is for repetitions; the scored version is on the
 * shadowing screen.
 */
object HandsFree {

    /** How much longer than the sentence to leave for saying it back. */
    private const val GAP_RATIO = 1.35f
    private const val MIN_GAP_MS = 1_200L
    private const val MAX_GAP_MS = 12_000L

    /** Length of the generated silence; gaps are clipped out of this one file. */
    private const val SILENCE_MS = 15_000
    private const val SAMPLE_RATE = 8_000
    private const val SILENCE_NAME = "handsfree-silence.wav"

    fun buildQueue(context: Context, clips: List<HandsFreeClip>): List<MediaItem> {
        if (clips.isEmpty()) return emptyList()
        val silence = Uri.fromFile(silenceFile(context)).toString()

        return clips.flatMap { clip ->
            val spoken = (clip.endMs - clip.startMs).coerceAtLeast(1)
            val gap = (spoken * GAP_RATIO).toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)

            listOf(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(File(clip.audioPath)).toString())
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startMs.coerceAtLeast(0))
                            .setEndPositionMs(clip.endMs)
                            .build()
                    )
                    // The sentence itself is the lock screen title, so a glance
                    // at the phone is one more reading of it.
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(clip.text)
                            .setArtist(clip.source)
                            .build()
                    )
                    .build(),
                MediaItem.Builder()
                    .setUri(silence)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(gap)
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(clip.text)
                            .setArtist("따라 말하세요")
                            .build()
                    )
                    .build(),
            )
        }
    }

    /**
     * A silent WAV, written once.
     *
     * The gap has to be a media item like any other, because a MediaController
     * can only be handed items - it cannot construct a silence source across the
     * process boundary. One file, clipped to whatever each gap needs, costs
     * 240 KB and avoids a file per sentence.
     */
    internal fun silenceFile(context: Context): File {
        val file = File(context.cacheDir, SILENCE_NAME)
        val samples = SAMPLE_RATE * SILENCE_MS / 1000
        val dataBytes = samples * 2
        if (file.exists() && file.length() == (dataBytes + 44).toLong()) return file

        file.outputStream().buffered().use { out ->
            fun le32(v: Int) = out.write(
                byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 24) and 0xFF).toByte(),
                )
            )
            fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))

            out.write("RIFF".toByteArray()); le32(36 + dataBytes); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
            le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
            out.write("data".toByteArray()); le32(dataBytes)

            val chunk = ByteArray(4096)
            var written = 0
            while (written < dataBytes) {
                val n = minOf(chunk.size, dataBytes - written)
                out.write(chunk, 0, n)
                written += n
            }
        }
        return file
    }
}
