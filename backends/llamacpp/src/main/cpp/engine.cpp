#include "engine.h"

#include <algorithm>
#include <chrono>
#include <mutex>

#include <dlfcn.h>

#ifdef __ANDROID__
#include <android/log.h>
#define DEN_LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, "den-llama", __VA_ARGS__)
#define DEN_LOG_WARN(text) __android_log_write(ANDROID_LOG_WARN, "den-llama", text)
#else
// Desktop builds, used by the engine tests in CI.
#include <cstdio>
#define DEN_LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define DEN_LOG_WARN(text) std::fputs(text, stderr)
#endif

#include "ggml-backend.h"
#include "json-schema-to-grammar.h"
#include <nlohmann/json.hpp>
#include "llama.h"
#include "threads.h"

namespace den {

namespace {

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// Length of the longest prefix of s that ends on a UTF-8 character boundary.
size_t utf8_complete(const std::string &s) {
    size_t i = s.size();
    int back = 0;
    while (i > 0 && back < 4) {
        auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : 4;
            return (size_t) back + 1 >= need ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

std::once_flag g_backends;

} // namespace

#ifdef __ANDROID__
// CPU variants built with GGML_CPU_ALL_VARIANTS. Each one scores itself on this CPU (0 when it cannot run here),
// the way ggml picks a variant from a folder, which an APK does not have.
static void load_best_cpu() {
    std::string best;
    int best_score = 0;
    for (const char *tag : {"android_armv9.2_2", "android_armv9.2_1", "android_armv9.0_1", "android_armv8.6_1",
                            "android_armv8.2_2", "android_armv8.2_1", "android_armv8.0_1"}) {
        std::string name = std::string("libggml-cpu-") + tag + ".so";
        void *h = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) continue;
        auto score = reinterpret_cast<int (*)()>(dlsym(h, "ggml_backend_score"));
        int s = score ? score() : 0;
        dlclose(h);
        if (s > best_score) {
            best_score = s;
            best = name;
        }
    }
    if (best.empty()) best = "libggml-cpu.so";
    ggml_backend_load(best.c_str());
    DEN_LOG_INFO("cpu backend %s (score %d)", best.c_str(), best_score);
}
#endif

std::vector<Device> init_backends(const std::string &lib_dir) {
    std::call_once(g_backends, [&] {
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            if (level >= GGML_LOG_LEVEL_WARN) DEN_LOG_WARN(text);
        }, nullptr);
        if (lib_dir.empty()) {
#ifdef __ANDROID__
            // In an APK the libraries are usually not extracted, so load by name; a missing one is skipped.
            load_best_cpu();
            ggml_backend_load("libggml-opencl.so");
#endif
            // Desktop builds link the CPU backend statically; nothing to load.
        } else {
            ggml_backend_load_all_from_path(lib_dir.c_str());
        }
        llama_backend_init();
    });
    std::vector<Device> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto *d = ggml_backend_dev_get(i);
        out.push_back({ggml_backend_dev_name(d), ggml_backend_dev_description(d),
                       ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU});
    }
    return out;
}

// A context with its own KV cache of n_ctx tokens; the threads are set again before each generation.
static llama_context *new_context(llama_model *model, int n_ctx, int n_threads) {
    Threads t = n_threads > 0 ? Threads{n_threads, n_threads} : pick_threads();
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = std::min(n_ctx, 512);
    cp.n_threads = t.decode;
    cp.n_threads_batch = t.batch;
    return llama_init_from_model(model, cp);
}

std::unique_ptr<Engine> Engine::load(const std::string &path, int n_ctx, int n_threads,
                                     int n_gpu_layers, bool use_mmap, std::string &err) {
    if (ggml_backend_dev_count() == 0) {
        err = "no ggml backend loaded; call init_backends first";
        return nullptr;
    }
    std::unique_ptr<Engine> e(new Engine());

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.use_mmap = use_mmap;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        err = "failed to load model: " + path;
        return nullptr;
    }
    e->model_ = std::shared_ptr<llama_model>(model, llama_model_free);
    e->threads_ = n_threads;
    e->n_ctx_ = n_ctx;
    e->ctx_ = new_context(e->model_.get(), n_ctx, n_threads);
    if (!e->ctx_) {
        err = "failed to create context";
        return nullptr;
    }
    return e;
}

std::unique_ptr<Engine> Engine::fork(std::string &err) const {
    std::unique_ptr<Engine> e(new Engine());
    e->model_ = model_;
    e->threads_ = threads_;
    e->n_ctx_ = n_ctx_;
    e->ctx_ = new_context(model_.get(), n_ctx_, threads_);
    if (!e->ctx_) {
        err = "failed to create a second context";
        return nullptr;
    }
    return e;
}

Engine::~Engine() {
    if (ctx_) llama_free(ctx_);
}

std::string Engine::format(const Messages &messages, std::string &err) const {
    std::vector<llama_chat_message> chat;
    size_t total = 0;
    for (const auto &m : messages) {
        chat.push_back({m.first.c_str(), m.second.c_str()});
        total += m.first.size() + m.second.size();
    }
    const char *tmpl = llama_model_chat_template(model_.get(), nullptr);
    std::string buf(2 * total + 256, '\0');
    int n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int) buf.size());
    }
    if (n < 0) {
        err = "unsupported chat template";
        return {};
    }
    buf.resize(n);
    return buf;
}

bool Engine::generate(const Messages &messages, const GenParams &params, const OnText &on_text,
                      GenStats &stats, std::string &err) {
    stats = {};
    std::string prompt = format(messages, err);
    if (!err.empty()) return false;

    const llama_vocab *vocab = llama_model_get_vocab(model_.get());
    int n = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), tokens.data(), n, true, true);

    const int n_ctx = (int) llama_n_ctx(ctx_);
    if (n >= n_ctx) {
        err = "prompt (" + std::to_string(n) + " tokens) does not fit in context " + std::to_string(n_ctx);
        return false;
    }
    const int max_new = std::min(params.max_tokens, n_ctx - n);

    Threads t = params.threads > 0 ? Threads{params.threads, params.threads}
              : threads_ > 0       ? Threads{threads_, threads_}
                                   : pick_threads();
    llama_set_n_threads(ctx_, t.decode, t.batch);
    stats.threads = t.decode;
    stats.threads_batch = t.batch;

    // The prompt prefix already in the KV cache, e.g. the history of a chat; at least one token is decoded again
    // for the logits of the next one.
    auto *mem = llama_get_memory(ctx_);
    int keep = 0;
    if (params.cache) {
        size_t limit = n > 1 ? std::min(cached_.size(), (size_t) (n - 1)) : 0;
        while ((size_t) keep < limit && cached_[keep] == tokens[keep]) ++keep;
    }
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    cached_.assign(tokens.begin(), tokens.begin() + keep);
    stats.prompt_tokens = n;
    stats.cached_tokens = keep;

    int64_t t0 = now_ms();
    const int n_batch = (int) llama_n_batch(ctx_);
    for (int i = keep; i < n; i += n_batch) {
        int len = std::min(n_batch, n - i);
        if (llama_decode(ctx_, llama_batch_get_one(tokens.data() + i, len)) != 0) {
            err = "prefill failed";
            cached_.clear();
            llama_memory_clear(mem, true);
            return false;
        }
        cached_.insert(cached_.end(), tokens.begin() + i, tokens.begin() + i + len);
    }
    int64_t t1 = now_ms();
    stats.prefill_ms = t1 - t0;

    // A grammar first in the chain, so the samplers after it only see tokens the grammar allows.
    std::string grammar = params.grammar;
    if (grammar.empty() && !params.json_schema.empty()) {
        try {
            grammar = json_schema_to_grammar(nlohmann::ordered_json::parse(params.json_schema));
        } catch (const std::exception &e) {
            err = std::string("invalid JSON schema: ") + e.what();
            return false;
        }
    }
    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        auto *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) {
            llama_sampler_free(smpl);
            err = "invalid grammar";
            return false;
        }
        llama_sampler_chain_add(smpl, g);
    }
    if (params.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(params.top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(params.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // Text is held back until it can no longer be the start of a stop string.
    size_t hold = 0;
    for (const auto &s : params.stop) hold = std::max(hold, s.size());
    std::string pending;
    bool go = true;
    char piece[256];

    bool ended = false;
    for (int i = 0; i < max_new && go; ++i) {
        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            ended = true;
            break;
        }
        stats.output_tokens++;

        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, len);

        bool stopped = false;
        for (const auto &s : params.stop) {
            auto pos = pending.find(s);
            if (!s.empty() && pos != std::string::npos) {
                pending.resize(pos);
                stopped = true;
            }
        }
        size_t ready = utf8_complete(pending);
        if (!stopped && hold > 0) ready = pending.size() > hold ? utf8_complete(pending.substr(0, pending.size() - hold)) : 0;
        if (ready > 0) {
            go = on_text(pending.substr(0, ready));
            pending.erase(0, ready);
        }
        if (stopped) {
            ended = true;
            break;
        }

        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) {
            err = "decode failed";
            break;
        }
        cached_.push_back(tok);
    }
    if (go && !pending.empty()) on_text(pending.substr(0, utf8_complete(pending)));

    if (!ended && go && stats.output_tokens >= max_new) stats.finish = max_new < params.max_tokens ? 2 : 1;
    stats.decode_ms = now_ms() - t1;
    llama_sampler_free(smpl);
    return err.empty();
}

} // namespace den
