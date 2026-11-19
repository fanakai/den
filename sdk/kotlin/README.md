# sdk/kotlin

The Kotlin SDK for Android apps. An app names a model, and Den picks the backend, accelerator and model file for the phone it runs on.

```kotlin
val den = Den.connect(context)
val model = den.model("qwen3-1.7b")          // router: recipe for this SoC, then fallbacks
model.chat("Why do foxes live in dens?").collect { event ->
    when (event) {
        is GenEvent.Text -> print(event.text)
        is GenEvent.Done -> println("\n${event.stats.decodeTps} tok/s")
    }
}
model.close()
```

`den.plan(name)` lists what the router would try, in order, without loading anything; `model.backend`, `model.accel` and `model.quant` tell what was chosen.

## What it contains

1) `AndroidDeviceProbe`: SoC, SoC family, RAM and free RAM, thermal status;
2) the repository `recipes/`, bundled under `assets/den/recipes`;
3) `BuiltinCatalog`: Qwen3 0.6B and 1.7B in Q4_0 and Q4_K_M, with their Hugging Face URL and SHA-256;
4) the llama.cpp backend (CPU, and Adreno GPU through OpenCL).

## Current limits

- The model files must already be in `<filesDir>/den/models` (or `DenConfig.modelDir`). Downloading comes with the model manager (#10), and the live catalog with #31.
- The engine runs in the app process. Connected mode, where several apps share the Den app, comes with #8.
