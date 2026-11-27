// Standalone check of the engine, runnable from Termux:
// den-smoke <model.gguf> [threads] [gpu_layers] [prompt | long | long:N]
// DEN_CTX=4096 sets the context, 2048 by default.
// The ggml backend libraries are loaded from the binary's own folder.
#include <unistd.h>

#include <cstdio>
#include <cstdlib>

#include "engine.h"

namespace {

std::string exe_dir() {
    char buf[4096];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    std::string p(buf, n);
    return p.substr(0, p.find_last_of('/'));
}

// About 24 tokens per repeat, to measure prefill on more than a handful of tokens.
std::string long_prompt(int repeats) {
    std::string s = "Summarize the following notes in one sentence. /no_think\n";
    for (int i = 0; i < repeats; ++i) {
        s += "The fennec fox lives in the Sahara and digs dens in the sand to escape the heat of the day. ";
    }
    return s;
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <model.gguf> [threads] [gpu_layers] [prompt | long | long:N]\n", argv[0]);
        return 2;
    }
    int threads = argc > 2 ? std::atoi(argv[2]) : 0;
    int gpu_layers = argc > 3 ? std::atoi(argv[3]) : 0;
    std::string prompt = argc > 4 ? argv[4] : "Name three colors. /no_think";
    if (prompt == "long") prompt = long_prompt(12);
    else if (prompt.rfind("long:", 0) == 0) prompt = long_prompt(std::atoi(prompt.c_str() + 5));
    const char *ctx = std::getenv("DEN_CTX");

    for (const auto &d : den::init_backends(exe_dir())) {
        std::printf("device %s: %s%s\n", d.name.c_str(), d.description.c_str(), d.npu ? " (npu)" : d.gpu ? " (gpu)" : "");
    }

    std::string err;
    // DEN_KV=q8_0: the q8_0 KV cache, as a recipe can choose it. DEN_DEVICE: the only device to offload to.
    const char *kv = std::getenv("DEN_KV");
    const char *device = std::getenv("DEN_DEVICE");
    auto engine = den::Engine::load(argv[1], ctx ? std::atoi(ctx) : 2048, threads, gpu_layers, std::getenv("DEN_NO_MMAP") == nullptr, err, 1,
                                    kv != nullptr && std::string(kv) == "q8_0", device ? device : "");
    if (!engine) {
        std::fprintf(stderr, "load: %s\n", err.c_str());
        return 1;
    }

    den::GenParams params;
    params.max_tokens = 64;
    params.temperature = 0.0f;
    // Each run measures a full prefill.
    params.cache = false;
    den::GenStats s;
    bool ok = engine->generate({{"user", prompt}}, params, [](const std::string &t) {
        std::fputs(t.c_str(), stdout);
        std::fflush(stdout);
        return true;
    }, s, err);
    if (!ok) {
        std::fprintf(stderr, "\ngenerate: %s\n", err.c_str());
        return 1;
    }
    std::printf("\n\nthreads %d/%d | prompt %d tok, %.1f tok/s | output %d tok, %.1f tok/s\n",
                s.threads, s.threads_batch, s.prompt_tokens,
                s.prefill_ms ? s.prompt_tokens * 1000.0 / s.prefill_ms : 0.0,
                s.output_tokens, s.decode_ms ? s.output_tokens * 1000.0 / s.decode_ms : 0.0);
    return 0;
}
