// JNI bridge between com.cuenote.ml.WhisperNative and whisper.cpp.
// Kept deliberately thin: Kotlin owns all decisions, this file only moves
// arrays across and turns whisper.cpp's results into plain values.
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

namespace {

std::string toString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Quiet whisper.cpp's console logging; the app reports its own progress.
void silentLog(enum ggml_log_level, const char *, void *) {}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cuenote_ml_WhisperNative_initContext(JNIEnv *env, jobject, jstring modelPath) {
    whisper_log_set(silentLog, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    std::string path = toString(env, modelPath);
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_cuenote_ml_WhisperNative_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

// Transcribes 16 kHz mono samples. Returns one line per word:
// "<start ms>\t<end ms>\t<text>", times relative to the first sample.
JNIEXPORT jstring JNICALL
Java_com_cuenote_ml_WhisperNative_transcribe(JNIEnv *env, jobject, jlong ptr, jfloatArray samples,
                                             jint threads, jstring language, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    std::string lang = toString(env, language);
    std::string initialPrompt = toString(env, prompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.c_str();
    params.translate = false;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    // One segment per word, each with its own times. The pipeline packs several
    // utterances into one call, and whole-sentence segments run across the gaps
    // between them; word times let each word go back to the utterance it came from.
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    if (!initialPrompt.empty()) params.initial_prompt = initialPrompt.c_str();

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    std::string out;
    if (whisper_full(ctx, params, pcm.data(), n) == 0) {
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            // whisper.cpp times are in 10 ms steps.
            long long t0 = whisper_full_get_segment_t0(ctx, i) * 10;
            long long t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            out += std::to_string(t0) + "\t" + std::to_string(t1) + "\t" +
                   whisper_full_get_segment_text(ctx, i) + "\n";
        }
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_cuenote_ml_WhisperNative_initVad(JNIEnv *env, jobject, jstring modelPath, jint threads) {
    whisper_log_set(silentLog, nullptr);
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = threads;
    params.use_gpu = false;
    std::string path = toString(env, modelPath);
    return reinterpret_cast<jlong>(whisper_vad_init_from_file_with_params(path.c_str(), params));
}

JNIEXPORT void JNICALL
Java_com_cuenote_ml_WhisperNative_freeVad(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_vad_free(reinterpret_cast<whisper_vad_context *>(ptr));
}

// Speech probability for each 512-sample (32 ms) frame of 16 kHz mono audio.
JNIEXPORT jfloatArray JNICALL
Java_com_cuenote_ml_WhisperNative_vadProbabilities(JNIEnv *env, jobject, jlong ptr, jfloatArray samples) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(ptr);
    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    if (!whisper_vad_detect_speech(vctx, pcm.data(), n)) return env->NewFloatArray(0);
    int count = whisper_vad_n_probs(vctx);
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, whisper_vad_probs(vctx));
    return result;
}

}  // extern "C"
