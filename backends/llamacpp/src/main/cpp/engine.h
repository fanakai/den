#pragma once

#include <condition_variable>
#include <cstdint>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

struct llama_model;
struct llama_context;

namespace den {

struct GenParams {
    int max_tokens = 512;
    float temperature = 0.7f;
    float top_p = 0.95f;
    std::vector<std::string> stop;
    // Reuse the KV cache of the previous call for the prompt prefix both share; false prefills it all again.
    bool cache = true;
    // Threads for this call (decode and batch); 0 keeps the engine's choice.
    int threads = 0;
    // Constrains the output: a GBNF grammar, or a JSON schema turned into one. Empty: free text.
    std::string grammar;
    std::string json_schema;
};

struct GenStats {
    int threads = 0;
    int threads_batch = 0;
    int prompt_tokens = 0;
    int output_tokens = 0;
    // Prompt tokens taken from the KV cache, not prefilled.
    int cached_tokens = 0;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    // 0: end of text or stop string, 1: max_tokens, 2: context full.
    int finish = 0;
};

// (role, content)
using Messages = std::vector<std::pair<std::string, std::string>>;

// Receives complete UTF-8 text only; return false to stop.
using OnText = std::function<bool(const std::string &)>;

struct Device {
    std::string name;
    std::string description;
    bool gpu;
};

// Loads the ggml backend libraries (once) and lists the devices they offer.
// Empty lib_dir loads them by name, as inside an APK.
std::vector<Device> init_backends(const std::string &lib_dir);

class Engine {
public:
    // n_threads <= 0 picks threads automatically before each generation. n_seq: requests that run at the same
    // time, each a sequence of n_ctx tokens in one context; they share each decoding step. kv_q8: a q8_0 KV cache,
    // with flash attention, instead of F16.
    static std::unique_ptr<Engine> load(const std::string &path, int n_ctx, int n_threads,
                                        int n_gpu_layers, bool use_mmap, std::string &err, int n_seq = 1,
                                        bool kv_q8 = false);
    ~Engine();

    // Safe to call from several threads at once: up to n_seq requests run together, further ones wait.
    bool generate(const Messages &messages, const GenParams &params, const OnText &on_text,
                  GenStats &stats, std::string &err);

    // Another engine on the same weights, with its own context and KV cache: a second request runs beside the first
    // instead of waiting. Null when the context cannot be made (memory).
    std::unique_ptr<Engine> fork(std::string &err) const;

private:
    struct Request;

    Engine() = default;
    std::string format(const Messages &messages, std::string &err) const;
    bool start(int n_seq, std::string &err);
    void run();

    // Shared by the forks: freed with the last one.
    std::shared_ptr<llama_model> model_;
    int n_ctx_ = 0;
    bool kv_q8_ = false;
    llama_context *ctx_ = nullptr;
    int threads_ = 0;

    // The scheduler: one worker thread decodes a batch per step for all running requests, and is the only one to
    // touch the context. mu_ guards the lists and what the requests and the worker exchange.
    std::mutex mu_;
    std::condition_variable cv_;
    std::list<Request *> waiting_;
    std::list<Request *> active_;
    // Per sequence, the worker's: the tokens in its KV cache, in order, and whether a request holds it.
    std::vector<std::vector<int32_t>> cached_;
    std::vector<bool> busy_;
    std::thread worker_;
    bool stop_ = false;
};

} // namespace den
