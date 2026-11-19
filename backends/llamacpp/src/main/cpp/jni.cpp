#include <jni.h>

#include <mutex>

#include "engine.h"

namespace {

struct Handle {
    std::unique_ptr<den::Engine> engine;
    std::mutex lock;
};

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<std::string> strs(JNIEnv *env, jobjectArray a) {
    std::vector<std::string> out;
    for (jsize i = 0; i < env->GetArrayLength(a); ++i) {
        auto s = (jstring) env->GetObjectArrayElement(a, i);
        out.push_back(str(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

void throw_engine(JNIEnv *env, const std::string &msg) {
    env->ThrowNew(env->FindClass("ai/fanak/den/backend/llamacpp/EngineException"), msg.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_load(JNIEnv *env, jobject, jstring path, jint n_ctx,
                                                     jint n_threads, jint n_gpu_layers) {
    std::string err;
    auto engine = den::Engine::load(str(env, path), n_ctx, n_threads, n_gpu_layers, err);
    if (!engine) {
        throw_engine(env, err);
        return 0;
    }
    auto *h = new Handle();
    h->engine = std::move(engine);
    return reinterpret_cast<jlong>(h);
}

// Returns [promptTokens, outputTokens, prefillMs, decodeMs].
JNIEXPORT jlongArray JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_generate(JNIEnv *env, jobject, jlong ptr,
                                                         jobjectArray roles, jobjectArray contents,
                                                         jint max_tokens, jfloat temperature, jfloat top_p,
                                                         jobjectArray stop, jobject callback) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    std::lock_guard<std::mutex> guard(h->lock);

    auto r = strs(env, roles);
    auto c = strs(env, contents);
    den::Messages messages;
    for (size_t i = 0; i < r.size(); ++i) messages.emplace_back(r[i], c[i]);

    den::GenParams params;
    params.max_tokens = max_tokens;
    params.temperature = temperature;
    params.top_p = top_p;
    params.stop = strs(env, stop);

    // Bytes, not a jstring: NewStringUTF uses modified UTF-8 and breaks 4-byte characters.
    jmethodID on_text = env->GetMethodID(env->GetObjectClass(callback), "onText", "([B)Z");
    auto cb = [&](const std::string &text) {
        jbyteArray bytes = env->NewByteArray((jsize) text.size());
        env->SetByteArrayRegion(bytes, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
        jboolean go = env->CallBooleanMethod(callback, on_text, bytes);
        env->DeleteLocalRef(bytes);
        return !env->ExceptionCheck() && go;
    };

    den::GenStats stats;
    std::string err;
    bool ok = h->engine->generate(messages, params, cb, stats, err);
    if (env->ExceptionCheck()) return nullptr;
    if (!ok) {
        throw_engine(env, err);
        return nullptr;
    }
    jlong out[4] = {stats.prompt_tokens, stats.output_tokens, stats.prefill_ms, stats.decode_ms};
    jlongArray arr = env->NewLongArray(4);
    env->SetLongArrayRegion(arr, 0, 4, out);
    return arr;
}

JNIEXPORT void JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    { std::lock_guard<std::mutex> guard(h->lock); }
    delete h;
}

} // extern "C"
