// Standalone check of the engine, runnable from Termux:
// den-smoke <model.gguf> [threads] [gpu_layers] [prompt | long]
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

// About 400 tokens, to measure prefill on more than a handful of tokens.
std::string long_prompt() {
    std::string s = "Summarize the following notes in one sentence. /no_think\n";
    for (int i = 0; i < 12; ++i) {
        s += "The fennec fox lives in the Sahara and digs dens in the sand to escape the heat of the day. ";
    }
    return s;
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <model.gguf> [threads] [gpu_layers] [prompt | long]\n", argv[0]);
        return 2;
    }
    int threads = argc > 2 ? std::atoi(argv[2]) : 0;
    int gpu_layers = argc > 3 ? std::atoi(argv[3]) : 0;
    std::string prompt = argc > 4 ? argv[4] : "Name three colors. /no_think";
    if (prompt == "long") prompt = long_prompt();

    for (const auto &d : den::init_backends(exe_dir())) {
        std::printf("device %s: %s%s\n", d.name.c_str(), d.description.c_str(), d.gpu ? " (gpu)" : "");
    }

    std::string err;
    auto engine = den::Engine::load(argv[1], 2048, threads, gpu_layers, err);
    if (!engine) {
        std::fprintf(stderr, "load: %s\n", err.c_str());
        return 1;
    }

    den::GenParams params;
    params.max_tokens = 64;
    params.temperature = 0.0f;
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
