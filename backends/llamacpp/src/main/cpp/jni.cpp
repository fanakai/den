#include <jni.h>

#include <mutex>

#include "engine.h"
#include "ggml.h"

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

// Strings sent as UTF-8 bytes: GetStringUTFChars gives modified UTF-8, which splits characters outside the BMP
// (emoji) into two surrogates that the tokenizer does not read.
std::vector<std::string> utf8s(JNIEnv *env, jobjectArray a) {
    std::vector<std::string> out;
    for (jsize i = 0; i < env->GetArrayLength(a); ++i) {
        auto b = (jbyteArray) env->GetObjectArrayElement(a, i);
        jsize n = env->GetArrayLength(b);
        std::string s((size_t) n, '\0');
        env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte *>(s.data()));
        out.push_back(std::move(s));
        env->DeleteLocalRef(b);
    }
    return out;
}

void throw_engine(JNIEnv *env, const std::string &msg) {
    env->ThrowNew(env->FindClass("ai/fanak/den/backend/llamacpp/EngineException"), msg.c_str());
}

} // namespace

extern "C" {

// Each entry is "name\tdescription\tgpu|cpu".
JNIEXPORT jobjectArray JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_devices(JNIEnv *env, jobject) {
    auto devices = den::init_backends("");
    jobjectArray out = env->NewObjectArray((jsize) devices.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < devices.size(); ++i) {
        std::string s = devices[i].name + "\t" + devices[i].description + "\t" + (devices[i].gpu ? "gpu" : "cpu");
        jstring js = env->NewStringUTF(s.c_str());
        env->SetObjectArrayElement(out, (jsize) i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

JNIEXPORT jlong JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_load(JNIEnv *env, jobject, jstring path, jint n_ctx,
                                                     jint n_threads, jint n_gpu_layers, jboolean use_mmap) {
    den::init_backends("");
    std::string err;
    auto engine = den::Engine::load(str(env, path), n_ctx, n_threads, n_gpu_layers, use_mmap, err);
    if (!engine) {
        throw_engine(env, err);
        return 0;
    }
    auto *h = new Handle();
    h->engine = std::move(engine);
    return reinterpret_cast<jlong>(h);
}

// Returns [promptTokens, outputTokens, prefillMs, decodeMs, finish].
JNIEXPORT jlongArray JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_generate(JNIEnv *env, jobject, jlong ptr,
                                                         jobjectArray roles, jobjectArray contents,
                                                         jint max_tokens, jfloat temperature, jfloat top_p,
                                                         jobjectArray stop, jboolean cache, jint threads,
                                                         jobjectArray constraints, jobject callback) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    std::lock_guard<std::mutex> guard(h->lock);

    auto r = strs(env, roles);
    auto c = utf8s(env, contents);
    den::Messages messages;
    for (size_t i = 0; i < r.size(); ++i) messages.emplace_back(r[i], c[i]);

    den::GenParams params;
    params.max_tokens = max_tokens;
    params.temperature = temperature;
    params.top_p = top_p;
    params.stop = utf8s(env, stop);
    params.cache = cache;
    params.threads = threads;
    // [grammar, json schema], each empty when unused.
    auto k = utf8s(env, constraints);
    if (k.size() > 0) params.grammar = k[0];
    if (k.size() > 1) params.json_schema = k[1];

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
    jlong out[7] = {stats.prompt_tokens, stats.output_tokens, stats.prefill_ms, stats.decode_ms, stats.finish,
                    stats.cached_tokens, stats.threads};
    jlongArray arr = env->NewLongArray(7);
    env->SetLongArrayRegion(arr, 0, 7, out);
    return arr;
}

JNIEXPORT void JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    { std::lock_guard<std::mutex> guard(h->lock); }
    delete h;
}

JNIEXPORT jstring JNICALL
Java_ai_fanak_den_backend_llamacpp_LlamaNative_version(JNIEnv *env, jobject) {
    std::string v = std::string(ggml_version()) + "-" + ggml_commit();
    return env->NewStringUTF(v.c_str());
}

} // extern "C"
