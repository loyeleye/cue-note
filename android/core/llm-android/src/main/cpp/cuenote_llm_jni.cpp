// JNI bridge between com.cuenote.pipeline.cards.LlamaNative and llama.cpp. Thin on
// purpose: Kotlin owns the prompt and the checks (PhoneCards); this file
// loads the model, applies its chat template, holds the answer to the JSON
// schema with a grammar and returns the text.
#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"
#include "json-schema-to-grammar.h"
#include <nlohmann/json.hpp>

namespace {

std::string toString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void silentLog(enum ggml_log_level, const char *, void *) {}

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int threads = 4;
};

// The model's own chat template around a system and a user turn, ready for the answer.
std::string chat(const llama_model *model, const std::string &system, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf(system.size() + user.size() + 1024);
    int n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) return system + "\n\n" + user + "\n\n";   // no template the library knows
    return std::string(buf.data(), n);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cuenote_pipeline_cards_LlamaNative_load(JNIEnv *env, jobject, jstring modelPath, jint nCtx, jint threads) {
    llama_log_set(silentLog, nullptr);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    std::string path = toString(env, modelPath);
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = (uint32_t) nCtx;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) { llama_model_free(model); return 0; }
    auto *s = new Session{model, ctx, threads};
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_cuenote_pipeline_cards_LlamaNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *s = reinterpret_cast<Session *>(ptr);
    if (s == nullptr) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

// One answer to a system and a user turn. An empty schema leaves the answer free.
// Returns the UTF-8 bytes (empty when the prompt doesn't fit the context);
// bytes rather than a jstring, since JNI strings can't carry every character.
JNIEXPORT jbyteArray JNICALL
Java_com_cuenote_pipeline_cards_LlamaNative_complete(JNIEnv *env, jobject, jlong ptr, jstring jSystem, jstring jUser,
                                          jstring jSchema, jint maxTokens, jfloat temperature, jint seed) {
    auto *s = reinterpret_cast<Session *>(ptr);
    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    std::string prompt = chat(s->model, toString(env, jSystem), toString(env, jUser));
    std::string schema = toString(env, jSchema);

    llama_memory_clear(llama_get_memory(s->ctx), true);

    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, true, true);
    if (n + maxTokens > (int) llama_n_ctx(s->ctx)) return env->NewByteArray(0);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!schema.empty()) {
        std::string grammar = json_schema_to_grammar(nlohmann::ordered_json::parse(schema));
        llama_sampler_chain_add(smpl, llama_sampler_init_grammar(vocab, grammar.c_str(), "root"));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));
    }

    std::string out;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    llama_token next = 0;
    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(s->ctx, batch) != 0) break;
        next = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        char piece[256];
        int len = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (len > 0) out.append(piece, len);
        batch = llama_batch_get_one(&next, 1);
    }
    llama_sampler_free(smpl);
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

}  // extern "C"
