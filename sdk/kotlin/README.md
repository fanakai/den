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

## Modes

`DenConfig.mode` decides where the model runs; the app code is the same in all three:

| Mode | Where |
|---|---|
| `AUTO` (default) | in the Den app when it is installed, so all apps share one download and one loaded copy; otherwise in the app process |
| `EMBEDDED` | always in the app process, with the app's own copy of the model |
| `CONNECTED` | always in the Den app; fails when it is not installed |

`model.connected` tells which one was used. In `AUTO`, if the Den app does not answer, the model runs in the app process instead.

## What it contains

1) `AndroidDeviceProbe`: SoC, SoC family, RAM and free RAM, thermal status;
2) the repository `recipes/`, bundled under `assets/den/recipes`;
3) `BuiltinCatalog`: Qwen3 0.6B and 1.7B in Q4_0 and Q4_K_M, with their Hugging Face URL and SHA-256;
4) the llama.cpp backend (CPU, and Adreno GPU through OpenCL);
5) the download of the chosen file on first use, checked against its SHA-256;
6) the AIDL client of the Den app.

## Current limits

- Only the built-in catalog; the live catalog comes with #31.
- The Den app asks the user once per app, by notification, on its first `model()` call; a denied app gets an error (in `AUTO`, it runs in the app process instead).
