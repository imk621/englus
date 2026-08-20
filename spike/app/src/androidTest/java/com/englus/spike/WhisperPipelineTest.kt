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

        // Only the first sampleCount entries are audio; the backing array is longer.
        val audio = result.samples.asList().subList(0, result.sampleCount)
        assertTrue("samples should be in [-1,1]", audio.all { it >= -1.01f && it <= 1.01f })
        assertTrue("audio should not be silent", audio.any { kotlin.math.abs(it) > 0.01f })
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
            "expected ~$expected samples, got ${result.sampleCount} " +
                "(drift ${result.sampleCount - expected})",
            kotlin.math.abs(result.sampleCount - expected) <= 100,
        )
        assertTrue(
            "audio should not be silent",
            result.samples.asList().subList(0, result.sampleCount).any { kotlin.math.abs(it) > 0.1f },
        )
        // The buffer is pre-sized from the container duration, so it must not have
        // had to grow — growing is what blows up memory on a 40-minute file.
        assertTrue(
            "backing array ${result.samples.size} unexpectedly far above $expected",
            result.samples.size < expected * 1.2,
        )
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

    /**
     * The decisive memory case. A 40-minute episode decodes to ~38.5M float
     * samples in ONE array — 154 MB. Everything about the buffer strategy
     * (pre-sizing from the container duration, 1.25x growth, handing back the
     * backing array instead of a trimmed copy) exists for this test, and none of
     * it is exercised by an 11-second clip.
     *
     * Needs audio/reference.mp3 on the device: ./tools/fetch-audio.sh
     */
    @Test
    fun decodesFortyMinuteEpisodeWithoutOom() {
        val file = File(appContext.getExternalFilesDir(null), "reference.mp3")
        assumeTrue("no reference.mp3 on device — run ./tools/fetch-audio.sh", file.exists())

        val result = AudioDecoder.decode(appContext, Uri.fromFile(file))

        assertTrue(
            "expected a long episode, got ${result.durationSec}s",
            result.durationSec > 600,
        )
        assertEquals("reference is 44.1 kHz", 44_100, result.sourceSampleRate)
        assertEquals("reference is stereo", 2, result.sourceChannels)

        // Pre-sizing must hold: a backing array much larger than the audio means
        // the buffer grew, which is the allocation spike this is guarding against.
        assertTrue(
            "backing array ${result.samples.size} vs ${result.sampleCount} samples — buffer grew",
            result.samples.size < result.sampleCount * 1.1,
        )
        assertTrue(
            "audio should not be silent",
            (0 until result.sampleCount step 10_000).any { kotlin.math.abs(result.samples[it]) > 0.01f },
        )
    }

    // --- model import -------------------------------------------------------

    /**
     * Importing through the file picker is the only way to install a model
     * without adb — Android 11+ blocks file managers from writing into
     * Android/data/<pkg> — so a sideloaded APK is useless if this path is broken.
     * Drives the real ViewModel so the state machine is covered too.
     */
    @Test
    fun importsPickedModelAndRejectsGarbage() {
        val source = findModel()
        assumeTrue("no model on device to import from", source != null)

        val app = appContext.applicationContext as android.app.Application
        val vm = SpikeViewModel(app)

        val staged = File(appContext.cacheDir, "ggml-import-test.bin")
        source!!.copyTo(staged, overwrite = true)

        vm.importModel(Uri.fromFile(staged))
        awaitIdle(vm)

        assertEquals("import should not error", null, vm.state.value.error)
        val imported = File(vm.modelsDir, "ggml-import-test.bin")
        assertTrue("model not copied to ${vm.modelsDir}", imported.exists())
        assertEquals("copied size differs", source.length(), imported.length())
        assertTrue(
            "imported model should be listed",
            vm.state.value.models.any { it.name == "ggml-import-test.bin" },
        )

        // A truncated or wrong file must be rejected at import, not surface later
        // as an opaque "model failed to load" in the middle of a benchmark.
        val garbage = File(appContext.cacheDir, "ggml-garbage.bin")
        garbage.writeBytes(ByteArray(1024) { 0x41 })
        vm.importModel(Uri.fromFile(garbage))
        awaitIdle(vm)

        assertTrue("garbage should be rejected: ${vm.state.value.error}", vm.state.value.error != null)
        assertTrue(
            "rejected file must not be left behind",
            !File(vm.modelsDir, "ggml-garbage.bin").exists(),
        )

        imported.delete()
    }

    /** importModel runs on viewModelScope (main dispatcher); wait it out. */
    private fun awaitIdle(vm: SpikeViewModel, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        // Give the coroutine a chance to flip `running` to true first.
        Thread.sleep(200)
        while (vm.state.value.running && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("timed out waiting for import", !vm.state.value.running)
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
                nSamples = pcm.sampleCount,
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
