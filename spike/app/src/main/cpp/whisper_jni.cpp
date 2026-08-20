// JNI bridge for the englus Phase 0 spike.
//
// The spike exists to answer one question: can this phone produce accurate
// WORD-LEVEL TIMESTAMPS for a podcast-length file, fast enough to run as a
// background job? So this bridge deliberately returns more than plain text --
// every token comes back with its t0/t1 and, when DTW is on, its t_dtw.
//
// Results are marshalled as a single JSON string rather than through a pile of
// accessor calls: it keeps the JNI surface to four functions, and the exact same
// payload can be dumped to a file and diffed against a reference transcript
// offline for WER measurement.

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "englus-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

void json_escape(const char *s, std::string &out) {
    if (s == nullptr) return;
    for (const unsigned char *p = (const unsigned char *) s; *p; ++p) {
        switch (*p) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (*p < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", *p);
                    out += buf;
                } else {
                    // UTF-8 continuation bytes pass through untouched.
                    out += (char) *p;
                }
        }
    }
}

void append_num(std::string &out, int64_t v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%lld", (long long) v);
    out += buf;
}

void append_float(std::string &out, float v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%.4f", v);
    out += buf;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_englus_spike_WhisperNative_systemInfo(JNIEnv *env, jobject /*thiz*/) {
    std::string info = whisper_print_system_info();
    info += " | whisper.cpp ";
    info += WHISPER_VERSION;
    return env->NewStringUTF(info.c_str());
}

// aheadsPreset maps to enum whisper_alignment_heads_preset. The caller picks the
// value matching the loaded model (BASE_EN, SMALL_EN, ...); a mismatched preset
// makes DTW timestamps silently wrong rather than failing loudly.
JNIEXPORT jlong JNICALL
Java_com_englus_spike_WhisperNative_initContext(JNIEnv *env, jobject /*thiz*/,
                                                jstring model_path, jboolean use_dtw,
                                                jint aheads_preset) {
    const std::string path = jstring_to_std(env, model_path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // CPU-only baseline; QNN/NPU is a separate spike.
    cparams.flash_attn = false;
    cparams.dtw_token_timestamps = use_dtw == JNI_TRUE;
    cparams.dtw_aheads_preset = (enum whisper_alignment_heads_preset) aheads_preset;

    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }
    LOGI("model loaded: %s (dtw=%d)", path.c_str(), (int) use_dtw);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_englus_spike_WhisperNative_freeContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr == 0) return;
    whisper_free((whisper_context *) (intptr_t) ptr);
}

JNIEXPORT jstring JNICALL
// n_samples is passed explicitly: the Kotlin side hands over the decoder's
// backing array, which is deliberately larger than the audio it holds. Reading
// GetArrayLength here would feed whisper a tail of silence.
Java_com_englus_spike_WhisperNative_transcribe(JNIEnv *env, jobject /*thiz*/,
                                               jlong ptr, jfloatArray audio, jint n_samples,
                                               jint n_threads, jstring language,
                                               jboolean token_timestamps, jboolean use_dtw,
                                               jint max_len, jboolean split_on_word) {
    auto *ctx = (whisper_context *) (intptr_t) ptr;
    if (ctx == nullptr) return env->NewStringUTF(R"({"ok":false,"error":"null context"})");

    const jsize capacity = env->GetArrayLength(audio);
    if (n_samples < 0 || n_samples > capacity) {
        return env->NewStringUTF(R"({"ok":false,"error":"n_samples out of range"})");
    }

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (samples == nullptr) return env->NewStringUTF(R"({"ok":false,"error":"no samples"})");

    const std::string lang = jstring_to_std(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads          = n_threads;
    params.language           = lang.empty() ? "en" : lang.c_str();
    params.translate          = false;
    params.print_realtime     = false;
    params.print_progress     = false;
    params.print_timestamps   = false;
    params.print_special      = false;
    params.no_timestamps      = false;
    params.token_timestamps   = token_timestamps == JNI_TRUE;
    params.split_on_word      = split_on_word == JNI_TRUE;
    params.max_len            = max_len;
    // Single pass. Fallbacks (temperature retry) would make the wall-clock number
    // unrepresentative of the steady-state cost we are trying to measure.
    params.temperature_inc    = 0.0f;

    whisper_reset_timings(ctx);
    const int rc = whisper_full(ctx, params, samples, n_samples);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF(R"({"ok":false,"error":"whisper_full failed"})");
    }

    const whisper_token eot = whisper_token_eot(ctx);
    const int n_segments = whisper_full_n_segments(ctx);

    std::string out;
    out.reserve(1 << 16);
    out += R"({"ok":true,"segments":[)";

    for (int i = 0; i < n_segments; ++i) {
        if (i > 0) out += ',';
        out += R"({"t0":)";
        append_num(out, whisper_full_get_segment_t0(ctx, i) * 10);   // centiseconds -> ms
        out += R"(,"t1":)";
        append_num(out, whisper_full_get_segment_t1(ctx, i) * 10);
        out += R"(,"text":")";
        json_escape(whisper_full_get_segment_text(ctx, i), out);
        out += R"(","tokens":[)";

        const int n_tokens = whisper_full_n_tokens(ctx, i);
        bool first = true;
        for (int j = 0; j < n_tokens; ++j) {
            const whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            if (td.id >= eot) continue;   // drop <|sot|>, timestamp tokens, etc.

            if (!first) out += ',';
            first = false;

            out += R"({"text":")";
            json_escape(whisper_token_to_str(ctx, td.id), out);
            out += R"(","t0":)";
            append_num(out, td.t0 * 10);
            out += R"(,"t1":)";
            append_num(out, td.t1 * 10);
            if (use_dtw == JNI_TRUE) {
                out += R"(,"tdtw":)";
                append_num(out, td.t_dtw * 10);
            }
            out += R"(,"p":)";
            append_float(out, td.p);
            out += '}';
        }
        out += "]}";
    }

    out += R"(],"timings":{)";
    if (const whisper_timings *t = whisper_get_timings(ctx)) {
        out += R"("encodeMs":)";
        append_float(out, t->encode_ms);
        out += R"(,"decodeMs":)";
        append_float(out, t->decode_ms);
        out += R"(,"sampleMs":)";
        append_float(out, t->sample_ms);
        out += R"(,"promptMs":)";
        append_float(out, t->prompt_ms);
        out += R"(,"batchdMs":)";
        append_float(out, t->batchd_ms);
    }
    out += "}}";

    return env->NewStringUTF(out.c_str());
}

} // extern "C"
