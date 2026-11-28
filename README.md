# Fanak Den

Den runs open language models on Android phones. An app names a model, for example `qwen3-1.7b`, and Den picks the backend, the accelerator and the model file for that phone. The Den app keeps one loaded copy of each model and shares it between apps, with the user's approval per app.

Google AICore does the same for Gemini Nano: one model on the phone that apps share. Den does it for open models, with open code, and with a public table of measured choices per chip that anyone can extend.

## What it does

- **Picks for the phone.** The router reads the chip, the RAM, the GPU and the thermal state, and follows the recipe measured for that chip (`recipes/`). When a choice fails to load, it falls back to the next one.
- **Shares one copy.** Apps connect to the Den app over AIDL. A model is downloaded once, checked against its SHA-256, and loaded once for all of them.
- **Speaks the OpenAI API.** The Den app can serve `/v1/chat/completions` on `127.0.0.1`, for code that already uses an OpenAI client ([docs/http-api.md](docs/http-api.md)).
- **Can leave the phone when allowed.** A model can run on an OpenAI-compatible server on the local network or in the cloud, when the user allows it for that app. The router then says why a request left the phone (the model does not fit, the phone is hot, the battery is low).

## Measured so far

The router follows these choices, in order, and falls back to the next one when a choice fails. Each row is measured on the phone itself, with Q4_0 and Q4_K_M files of Qwen3, a prompt of about 300 tokens and greedy decoding. The rows of 2026-11-21 come from `den bench` (128 tokens, median of 3 runs) on Samsung Remote Test Lab phones; the others from `den-smoke` over adb (64 tokens, mean of 2 or 3 runs). Each recipe file names its protocol in full.

<!-- Made from the recipe files by RecipeTableTest; do not edit. -->
| Phone | Model size | First choice | Then | Prefill (tok/s) | Decode (tok/s) | Measured |
|---|---|---|---|---|---|---|
| Galaxy A15 (MT6789V) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 65.9 | 14.6 | 2026-11-21 |
| Galaxy Z Flip7 (S5E9955) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 262.0 | 18.3 | 2026-11-21 |
| Galaxy Z Flip7 (S5E9955) | Qwen3 1.7B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 107.0 | 13.7 | 2026-11-21 |
| Galaxy A52s 5G (SM7325) | Qwen3 0.6B | litertlm, GPU, INT4 | llamacpp, CPU, Q4_0; llamacpp, CPU, Q4_K_M | 196.6 | 19.8 | 2026-11-28 |
| Galaxy A52s 5G (SM7325) | Qwen3 1.7B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 75.0 | 10.7 | 2026-11-28 |
| Galaxy S25 (SM8750) | Qwen3 0.6B | litertlm, GPU, INT4 | llamacpp, CPU, Q4_0; llamacpp, NPU, Q4_0; llamacpp, GPU, Q4_0; litertlm, CPU, INT4; llamacpp, CPU, Q4_K_M | 541.5 | 80.3 | 2026-11-27 |
| Galaxy S25 (SM8750) | Qwen3 1.7B | litertlm, GPU, INT4 | llamacpp, NPU, Q4_0; llamacpp, GPU, Q4_0; llamacpp, CPU, Q4_0; litertlm, CPU, INT4; llamacpp, CPU, Q4_K_M | 297.5 | 32.8 | 2026-11-28 |
| ZTE Blade A75 5G (T760) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 166.4 | 13.6 | 2026-11-20 |
<!-- End of the generated table. -->

How the recipes work, and how to add one from your own phone (the easiest way to contribute), is in [recipes/README.md](recipes/README.md).

## Use it from an app

```kotlin
val den = Den.connect(context)            // the Den app when installed, otherwise in the app's own process
val model = den.model("qwen3-1.7b")       // downloads and loads on first use
model.chat("Give three facts about the fennec fox.").collect { e ->
    if (e is GenEvent.Text) print(e.text)
}
```

`DenMode.CONNECTED` uses only the Den app, and `DenMode.EMBEDDED` runs in the app's own process (add `backends/llamacpp` for it). `apps/demo/` is a complete app that uses only the SDK.

The SDK is not on Maven Central yet; build it from source for now (Android 9, API 28, or later).

## Build

Requirements: JDK 17, Android SDK with platform 36, NDK `29.0.14206865` and CMake `3.22.1`.

```sh
git clone --recurse-submodules https://github.com/fanakai/den.git
cd den
./gradlew build                          # compile, unit tests and lint for every module
./gradlew :apps:den:assembleRelease      # the Den app
```

## Layout

| Path | What it holds |
|---|---|
| `core/` | Router, backend interface, model files, recipes (Kotlin Multiplatform, no Android dependency). |
| `service/` | The OpenAI-compatible HTTP server. |
| `backends/llamacpp/` | llama.cpp through JNI, CPU and Adreno OpenCL. |
| `backends/openai/` | OpenAI-compatible servers on the local network or in the cloud. |
| `sdk/kotlin/` | Android SDK: device probe, catalog, downloads, connected mode. |
| `apps/den/` | The Den app: shared models, app approvals, HTTP endpoint. |
| `recipes/` | Measured best choices per chip, by model size. |

More in [docs/](docs/): the AIDL interface, the HTTP API, privacy and the threat model. [AGENTS.md](AGENTS.md) holds the rules for contributors and coding agents.

## Status

Early and Android only. llama.cpp is the one on-phone backend today; LiteRT-LM and the NPUs are planned (milestone M6). Plans and progress are in the [issues](https://github.com/fanakai/den/issues) and [milestones](https://github.com/fanakai/den/milestones).

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Security reports: [SECURITY.md](SECURITY.md).
