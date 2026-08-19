package com.englus.spike

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the ASR pipeline: asset -> MediaCodec -> resampler ->
 * whisper.cpp -> token timestamps.
 *
 * This runs on an emulator, where it verifies CORRECTNESS only. Every timing
 * number it could produce would describe the host PC, not a Galaxy S25, so the
 * test asserts nothing about speed — that measurement only exists on the phone.
 *
 * Requires a model on the device:
 *   ./tools/fetch-model.sh tiny.en
 */
@RunWith(AndroidJUnit4::class)
class WhisperPipelineTest {

    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asset(name: String): Uri {
        val out = File(appContext.cacheDir, name)
        if (!out.exists() || out.length() == 0L) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(out)
    }

    /** Mirrors [SpikeViewModel.refreshModels]: `files/models/` or `files/` itself. */
    private fun findModel(): File? {
        val root = appContext.getExternalFilesDir(null)
        return listOfNotNull(File(root, "models"), root)
            .flatMap { it.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toList().orEmpty() }
            .minByOrNull { it.length() }   // smallest model = fastest emulator run
    }

    // --- decoding -----------------------------------------------------------

    /** jfk.wav is already 16 kHz mono, so this isolates decoding from resampling. */
    @Test
    fun decodesWavToSixteenKilohertzMono() {
        val result = AudioDecoder.decode(appContext, asset("jfk.wav"))

        assertEquals("source should already be 16 kHz", 16_000, result.sourceSampleRate)
        assertEquals("source should already be mono", 1, result.sourceChannels)
        assertTrue("expected ~11 s, got ${result.durationSec}", result.durationSec in 10.0..12.0)
        assertTrue("samples should be in [-1,1]", result.samples.all { it >= -1.01f && it <= 1.01f })
        assertTrue("audio should not be silent", result.samples.any { kotlin.math.abs(it) > 0.01f })
    }

    /**
     * The resampler is the part most likely to be subtly wrong, so it is tested
     * against synthesized audio of exactly known length rather than against a
     * sample file. Both bundled jfk clips happen to be 16 kHz mono already, so
     * they exercise no resampling at all — checking one against the other would
     * assert nothing.
     *
     * A per-chunk off-by-one is the failure that matters: inaudible in 11 seconds,
     * but it drifts subtitles seconds out of sync over an hour-long podcast.
     * Exact sample count is the only assertion that catches it.
     */
    @Test
    fun resamples44kStereoToExactly16kMono() {
        val seconds = 5
        val srcRate = 44_100
        val expected = seconds * AudioDecoder.TARGET_SAMPLE_RATE   // 80_000

        val result = AudioDecoder.decode(appContext, synthesizeWav(seconds, srcRate, channels = 2))

        assertEquals("source rate", srcRate, result.sourceSampleRate)
        assertEquals("source channels", 2, result.sourceChannels)
        assertTrue(
            "expected ~$expected samples, got ${result.samples.size} " +
                "(drift ${result.samples.size - expected})",
            kotlin.math.abs(result.samples.size - expected) <= 100,
        )
        assertTrue("audio should not be silent", result.samples.any { kotlin.math.abs(it) > 0.1f })
    }

    /** mp3 goes through a real codec rather than the raw-PCM path. */
    @Test
    fun decodesMp3() {
        val wav = AudioDecoder.decode(appContext, asset("jfk.wav"))
        val mp3 = AudioDecoder.decode(appContext, asset("jfk.mp3"))

        // mp3 encoding pads the stream, so allow a modest absolute difference.
        val drift = kotlin.math.abs(mp3.durationSec - wav.durationSec)
        assertTrue(
            "duration drift ${"%.3f".format(drift)}s (wav ${wav.durationSec}, mp3 ${mp3.durationSec})",
            drift < 0.25,
        )
    }

    /** 16-bit PCM WAV of a 440 Hz tone, written to cache and returned as a Uri. */
    private fun synthesizeWav(seconds: Int, sampleRate: Int, channels: Int): Uri {
        val frames = seconds * sampleRate
        val dataBytes = frames * channels * 2
        val buf = java.nio.ByteBuffer.allocate(44 + dataBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16)
        buf.putShort(1)                                        // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)                  // byte rate
        buf.putShort((channels * 2).toShort())                 // block align
        buf.putShort(16)                                       // bits per sample
        buf.put("data".toByteArray()); buf.putInt(dataBytes)

        for (i in 0 until frames) {
            val v = (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000).toInt().toShort()
            repeat(channels) { buf.putShort(v) }
        }

        val out = File(appContext.cacheDir, "synth_${sampleRate}_${channels}ch_${seconds}s.wav")
        out.writeBytes(buf.array())
        return Uri.fromFile(out)
    }

    // --- transcription ------------------------------------------------------

    @Test
    fun transcribesWithMonotonicWordTimestamps() {
        val model = findModel()
        assumeTrue(
            "no model in ${appContext.getExternalFilesDir(null)}/models — run ./tools/fetch-model.sh tiny.en",
            model != null,
        )

        val pcm = AudioDecoder.decode(appContext, asset("jfk.wav"))
        val aheads = WhisperNative.Aheads.forModelFile(model!!.name)
        val ptr = WhisperNative.initContext(model.absolutePath, true, aheads)
        assertTrue("model failed to load: ${model.name}", ptr != 0L)

        val json = try {
            WhisperNative.transcribe(
                ptr = ptr,
                audio = pcm.samples,
                nThreads = 4,
                language = "en",
                tokenTimestamps = true,
                useDtw = true,
                maxLen = 0,
                splitOnWord = true,
            )
        } finally {
            WhisperNative.freeContext(ptr)
        }

        val root = JSONObject(json)
        assertTrue("native call failed: $json", root.optBoolean("ok"))

        val segments = root.getJSONArray("segments")
        assertTrue("no segments returned", segments.length() > 0)

        val text = buildString {
            for (i in 0 until segments.length()) append(segments.getJSONObject(i).getString("text"))
        }.lowercase()
        assertTrue(
            "unexpected transcript: $text",
            text.contains("ask not what your country can do for you"),
        )

        // The point of the whole spike: usable per-word timing.
        var tokenCount = 0
        var lastEnd = -1L
        val audioMs = (pcm.durationSec * 1000).toLong()
        for (i in 0 until segments.length()) {
            val tokens = segments.getJSONObject(i).getJSONArray("tokens")
            for (j in 0 until tokens.length()) {
                val t = tokens.getJSONObject(j)
                val t0 = t.getLong("t0")
                val t1 = t.getLong("t1")
                tokenCount++

                assertTrue("token $tokenCount: t1 ($t1) < t0 ($t0)", t1 >= t0)
                assertTrue("token $tokenCount: t0 $t0 outside audio (0..$audioMs)", t0 in 0..audioMs + 500)
                assertTrue("token $tokenCount: went backwards ($t0 < $lastEnd)", t0 >= lastEnd - 500)
                assertTrue("token $tokenCount: missing tdtw", t.has("tdtw"))
                lastEnd = t1
            }
        }
        assertTrue("expected a token per word, got $tokenCount", tokenCount >= 15)
    }
}
