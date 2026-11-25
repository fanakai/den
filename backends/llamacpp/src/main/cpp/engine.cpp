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

// One context with n_seq sequences of n_ctx tokens each; the threads are set again before each step.
static llama_context *new_context(llama_model *model, int n_ctx, int n_threads, int n_seq, bool kv_q8) {
    Threads t = n_threads > 0 ? Threads{n_threads, n_threads} : pick_threads();
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx * n_seq;
    cp.n_seq_max = n_seq;
    cp.n_batch = std::min(n_ctx * n_seq, 512);
    cp.n_threads = t.decode;
    cp.n_threads_batch = t.batch;
    // A quantized V cache needs flash attention. On the A52s it decodes faster than F16 (#161, #170).
    if (kv_q8) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    return llama_init_from_model(model, cp);
}

struct Engine::Request {
    std::vector<llama_token> tokens;
    int seq = 0;
    // Tokens of this request's sequence in the KV cache, and prompt tokens decoded so far.
    int pos = 0;
    int next = 0;
    int max_new = 0;
    bool cache = true;
    int keep = 0;
    Threads threads;
    llama_sampler *smpl = nullptr;
    // Sampled, not yet decoded: the next step decodes it.
    bool has_last = false;
    llama_token last = 0;
    int sampled = 0;
    // Index in the current batch of the logits this request samples from; -1: none.
    int idx = -1;
    std::vector<llama_token> out;
    bool cancel = false;
    bool eog = false;
    bool done = false;
    std::string err;
    int64_t t0 = 0;
    int64_t t1 = 0;
};

bool Engine::start(int n_seq, std::string &err) {
    ctx_ = new_context(model_.get(), n_ctx_, threads_, n_seq, kv_q8_);
    if (!ctx_) {
        err = "failed to create context";
        return false;
    }
    cached_.assign(n_seq, {});
    busy_.assign(n_seq, false);
    worker_ = std::thread([this] { run(); });
    return true;
}

std::unique_ptr<Engine> Engine::load(const std::string &path, int n_ctx, int n_threads,
                                     int n_gpu_layers, bool use_mmap, std::string &err, int n_seq,
                                     bool kv_q8) {
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
    e->kv_q8_ = kv_q8;
    if (!e->start(std::max(n_seq, 1), err)) return nullptr;
    return e;
}

std::unique_ptr<Engine> Engine::fork(std::string &err) const {
    std::unique_ptr<Engine> e(new Engine());
    e->model_ = model_;
    e->threads_ = threads_;
    e->n_ctx_ = n_ctx_;
    e->kv_q8_ = kv_q8_;
    if (!e->start((int) busy_.size(), err)) {
        err = "failed to create a second context";
        return nullptr;
    }
    return e;
}

Engine::~Engine() {
    if (worker_.joinable()) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            stop_ = true;
        }
        cv_.notify_all();
        worker_.join();
    }
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

// Each step decodes one batch: the last sampled token of each running request, then prompt tokens of the
// requests still in prefill, as many as the batch holds. The lock is released while the batch decodes.
void Engine::run() {
    const int n_batch = (int) llama_n_batch(ctx_);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    auto *mem = llama_get_memory(ctx_);
    const llama_vocab *vocab = llama_model_get_vocab(model_.get());
    Threads set{0, 0};
    auto finish = [&](Request *r) {
        r->done = true;
        busy_[r->seq] = false;
        active_.remove(r);
    };
    auto add = [&](Request *r, llama_token tok, bool logits) {
        int i = batch.n_tokens++;
        batch.token[i] = tok;
        batch.pos[i] = r->pos++;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = r->seq;
        batch.logits[i] = logits;
        cached_[r->seq].push_back(tok);
        if (logits) r->idx = i;
    };
    // The free sequence whose KV cache shares the longest prefix with the prompt, e.g. the history of a chat; at
    // least one token is decoded again for the logits of the next one.
    auto admit = [&](Request *r) {
        const int n = (int) r->tokens.size();
        int keep = -1;
        for (int s = 0; s < (int) busy_.size(); ++s) {
            if (busy_[s]) continue;
            int k = 0;
            if (r->cache) {
                const auto &c = cached_[s];
                size_t limit = n > 1 ? std::min(c.size(), (size_t) (n - 1)) : 0;
                while ((size_t) k < limit && c[k] == r->tokens[k]) ++k;
            }
            if (k > keep) {
                keep = k;
                r->seq = s;
            }
        }
        if (keep == 0 || !llama_memory_seq_rm(mem, r->seq, keep, -1)) {
            llama_memory_seq_rm(mem, r->seq, -1, -1);
            keep = 0;
        }
        cached_[r->seq].resize(keep);
        busy_[r->seq] = true;
        r->keep = r->pos = r->next = keep;
        r->t0 = now_ms();
        active_.push_back(r);
    };

    std::unique_lock<std::mutex> lk(mu_);
    while (true) {
        cv_.wait(lk, [&] { return stop_ || !active_.empty() || !waiting_.empty(); });
        if (stop_) break;
        bool changed = false;
        for (auto it = active_.begin(); it != active_.end();) {
            Request *r = *it++;
            if (r->cancel) finish(r), changed = true;
        }
        for (auto it = waiting_.begin(); it != waiting_.end();) {
            Request *r = *it;
            if (r->cancel) {
                r->done = true;
                it = waiting_.erase(it);
                changed = true;
            } else if (std::find(busy_.begin(), busy_.end(), false) != busy_.end()) {
                it = waiting_.erase(it);
                admit(r);
            } else {
                break;
            }
        }
        if (changed) cv_.notify_all();
        if (active_.empty()) continue;
        Threads t = active_.front()->threads;
        // Only this thread changes the fields of a running request below, and frees one only under the lock.
        std::vector<Request *> in(active_.begin(), active_.end());
        lk.unlock();

        if (t.decode != set.decode || t.batch != set.batch) {
            llama_set_n_threads(ctx_, t.decode, t.batch);
            set = t;
        }
        batch.n_tokens = 0;
        for (Request *r : in) {
            r->idx = -1;
            if (r->has_last) {
                add(r, r->last, true);
                r->has_last = false;
            }
        }
        for (Request *r : in) {
            int room = n_batch - batch.n_tokens;
            int left = (int) r->tokens.size() - r->next;
            if (left <= 0 || room <= 0) continue;
            int len = std::min(room, left);
            for (int k = 0; k < len; ++k) add(r, r->tokens[r->next + k], r->next + k + 1 == (int) r->tokens.size());
            r->next += len;
        }

        bool failed = llama_decode(ctx_, batch) != 0;
        int64_t now = now_ms();
        std::vector<std::pair<Request *, llama_token>> got;
        if (!failed) {
            for (Request *r : in) {
                if (r->idx < 0) continue;
                if (r->sampled == 0) r->t1 = now;
                got.emplace_back(r, llama_sampler_sample(r->smpl, ctx_, r->idx));
            }
        } else {
            for (Request *r : in) {
                cached_[r->seq].clear();
                llama_memory_seq_rm(mem, r->seq, -1, -1);
            }
        }

        lk.lock();
        if (failed) {
            for (Request *r : in) {
                r->err = r->sampled == 0 ? "prefill failed" : "decode failed";
                finish(r);
            }
        }
        for (auto &[r, tok] : got) {
            if (llama_vocab_is_eog(vocab, tok)) {
                r->eog = true;
                finish(r);
                continue;
            }
            r->out.push_back(tok);
            if (++r->sampled >= r->max_new) {
                finish(r);
                continue;
            }
            r->last = tok;
            r->has_last = true;
        }
        cv_.notify_all();
    }
    llama_batch_free(batch);
}

bool Engine::generate(const Messages &messages, const GenParams &params, const OnText &on_text,
                      GenStats &stats, std::string &err) {
    stats = {};
    std::string prompt = format(messages, err);
    if (!err.empty()) return false;

    const llama_vocab *vocab = llama_model_get_vocab(model_.get());
    int n = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    Request r;
    r.tokens.resize(n);
    llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), r.tokens.data(), n, true, true);

    if (n >= n_ctx_) {
        err = "prompt (" + std::to_string(n) + " tokens) does not fit in context " + std::to_string(n_ctx_);
        return false;
    }
    r.max_new = std::min(params.max_tokens, n_ctx_ - n);

    r.threads = params.threads > 0 ? Threads{params.threads, params.threads}
              : threads_ > 0       ? Threads{threads_, threads_}
                                   : pick_threads();
    stats.threads = r.threads.decode;
    stats.threads_batch = r.threads.batch;

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
    r.smpl = smpl;

    r.cache = params.cache;
    std::unique_lock<std::mutex> lk(mu_);
    waiting_.push_back(&r);
    cv_.notify_all();

    // Text is held back until it can no longer be the start of a stop string.
    size_t hold = 0;
    for (const auto &s : params.stop) hold = std::max(hold, s.size());
    std::string pending;
    bool go = true;
    bool stopped = false;
    char piece[256];

    while (go && !stopped) {
        cv_.wait(lk, [&] { return !r.out.empty() || r.done; });
        std::vector<llama_token> toks;
        toks.swap(r.out);
        bool done = r.done;
        lk.unlock();
        for (llama_token tok : toks) {
            stats.output_tokens++;
            int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
            if (len > 0) pending.append(piece, len);

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
            if (stopped || !go) break;
        }
        lk.lock();
        if (done && r.out.empty()) break;
    }
    if (!r.done) {
        r.cancel = true;
        cv_.notify_all();
        cv_.wait(lk, [&] { return r.done; });
    }
    lk.unlock();
    if (go && !pending.empty()) on_text(pending.substr(0, utf8_complete(pending)));

    err = r.err;
    stats.prompt_tokens = n;
    stats.cached_tokens = r.keep;
    bool ended = r.eog || stopped;
    if (!ended && go && err.empty() && stats.output_tokens >= r.max_new) stats.finish = r.max_new < params.max_tokens ? 2 : 1;
    int64_t end = now_ms();
    stats.prefill_ms = r.t0 ? (r.t1 ? r.t1 : end) - r.t0 : 0;
    stats.decode_ms = r.t1 ? end - r.t1 : 0;
    llama_sampler_free(smpl);
    return err.empty();
}

} // namespace den
