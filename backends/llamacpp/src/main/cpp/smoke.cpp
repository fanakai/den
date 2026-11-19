// Standalone check of the engine, runnable from Termux: den-smoke <model.gguf> [threads] [prompt]
#include <cstdio>
#include <cstdlib>

#include "engine.h"

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <model.gguf> [threads] [prompt]\n", argv[0]);
        return 2;
    }
    int threads = argc > 2 ? std::atoi(argv[2]) : 4;
    std::string prompt = argc > 3 ? argv[3] : "Name three colors. /no_think";

    std::string err;
    auto engine = den::Engine::load(argv[1], 2048, threads, 0, err);
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
    std::printf("\n\nprompt %d tok, %.1f tok/s | output %d tok, %.1f tok/s\n",
                s.prompt_tokens, s.prefill_ms ? s.prompt_tokens * 1000.0 / s.prefill_ms : 0.0,
                s.output_tokens, s.decode_ms ? s.output_tokens * 1000.0 / s.decode_ms : 0.0);
    return 0;
}
