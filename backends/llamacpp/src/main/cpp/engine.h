#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
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
    // n_threads <= 0 picks threads automatically before each generation.
    static std::unique_ptr<Engine> load(const std::string &path, int n_ctx, int n_threads,
                                        int n_gpu_layers, bool use_mmap, std::string &err);
    ~Engine();

    bool generate(const Messages &messages, const GenParams &params, const OnText &on_text,
                  GenStats &stats, std::string &err);

private:
    Engine() = default;
    std::string format(const Messages &messages, std::string &err) const;

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    int threads_ = 0;
    // The tokens in the KV cache, in order.
    std::vector<int32_t> cached_;
};

} // namespace den
