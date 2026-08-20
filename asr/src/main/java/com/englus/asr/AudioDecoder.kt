package com.englus.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes any container/codec the platform knows (mp3, m4a, opus, wav, ...) into
 * the 16 kHz mono float PCM whisper expects.
 *
 * Decoding and resampling are fused into one streaming pass. The obvious
 * two-pass version — decode everything, then resample — would hold a full
 * source-rate copy in memory: a 30-minute podcast at 48 kHz is ~345 MB of float,
 * which OOMs. Here only one codec chunk exists at source rate at a time.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    /**
     * [samples] is the raw backing array and may be LONGER than [sampleCount].
     * Only the first [sampleCount] entries are audio.
     *
     * Handing back the backing array instead of a right-sized copy matters at
     * podcast length: 40 minutes is 38.5M samples = 154 MB of float, and copying
     * it once more to trim the tail would double the peak.
     */
    class Result(
        val samples: FloatArray,
        val sampleCount: Int,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val decodeMs: Long,
    ) {
        val durationSec: Double get() = sampleCount.toDouble() / TARGET_SAMPLE_RATE
    }

    class DecodeException(message: String) : Exception(message)

    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val started = System.currentTimeMillis()
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                inputFormat = format
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw DecodeException("오디오 트랙을 찾을 수 없습니다")
        }

        extractor.selectTrack(trackIndex)
        val durationUs = runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        // Source rate/channels come from the OUTPUT format, which the codec may
        // only report after the first buffer (and may change mid-stream).
        var srcRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        // Size the output buffer from the declared duration. Growing by doubling
        // from a small seed is what actually causes OOM on long files: a
        // 40-minute episode needs 154 MB, doubling overshoots to 268 MB, and
        // during the grow the old 134 MB array is still live -- ~400 MB peak for
        // 154 MB of audio. Allocating once, slightly over, avoids all of it.
        val estimated = if (durationUs > 0) {
            ((durationUs / 1_000_000.0) * TARGET_SAMPLE_RATE * 1.02).toInt() + 4096
        } else {
            1 shl 16
        }
        val out = FloatBuffer(estimated)
        var resampler = Resampler(srcRate, TARGET_SAMPLE_RATE)
        var monoScratch = FloatArray(0)

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var lastReported = -1f

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) {
                                val p = (extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastReported >= 0.01f) {
                                    onProgress(p); lastReported = p
                                }
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = runCatching { f.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                            .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                        resampler = Resampler(srcRate, TARGET_SAMPLE_RATE)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)

                                val frames = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> info.size / 4 / srcChannels
                                    else -> info.size / 2 / srcChannels
                                }
                                if (monoScratch.size < frames) monoScratch = FloatArray(frames)

                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                    for (i in 0 until frames) {
                                        var acc = 0f
                                        for (c in 0 until srcChannels) acc += fb.get()
                                        monoScratch[i] = acc / srcChannels
                                    }
                                } else {
                                    val sb = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                                    for (i in 0 until frames) {
                                        var acc = 0f
                                        for (c in 0 until srcChannels) acc += sb.get() / 32768f
                                        monoScratch[i] = acc / srcChannels
                                    }
                                }

                                resampler.process(monoScratch, frames, out)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        onProgress(1f)
        if (out.size == 0) throw DecodeException("디코딩 결과가 비어 있습니다")

        return Result(
            samples = out.backingArray(),
            sampleCount = out.size,
            sourceSampleRate = srcRate,
            sourceChannels = srcChannels,
            decodeMs = System.currentTimeMillis() - started,
        )
    }

    private const val TIMEOUT_US = 10_000L

    /**
     * Linear-interpolating resampler that survives chunk boundaries by carrying
     * the previous chunk's last sample as virtual index -1.
     */
    private class Resampler(srcRate: Int, dstRate: Int) {
        private val step = srcRate.toDouble() / dstRate
        private var pos = 0.0
        private var prev = 0f

        fun process(chunk: FloatArray, n: Int, out: FloatBuffer) {
            if (n == 0) return
            while (pos < n - 1) {
                val i = kotlin.math.floor(pos).toInt()
                val frac = (pos - i).toFloat()
                val a = if (i < 0) prev else chunk[i]
                val b = chunk[i + 1]
                out.add(a + (b - a) * frac)
                pos += step
            }
            prev = chunk[n - 1]
            pos -= n   // this chunk's last sample becomes the next chunk's index -1
        }
    }

    /** Growable primitive float list — avoids boxing millions of samples. */
    private class FloatBuffer(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(4096))
        var size = 0
            private set

        fun add(v: Float) {
            // Grows by 1.25x, not 2x. Once the buffer is pre-sized from the
            // declared duration this should never fire; if it does (a container
            // whose duration lies), overshooting by 154 MB is not affordable.
            if (size == data.size) data = data.copyOf(data.size + data.size / 4 + 4096)
            data[size++] = v
        }

        fun backingArray(): FloatArray = data
    }
}
