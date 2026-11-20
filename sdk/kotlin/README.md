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

Prompts go only to a Den app signed with a certificate the SDK trusts (`DEN_CERTIFICATES`: the release key, and the maintainer's debug key for test builds), since any app can be installed under the name `ai.fanak.den`. Another signer counts as "not installed" in `AUTO`, and is refused in `CONNECTED`. `DenConfig.denCertificates` replaces the list, for example with the debug key of your own Den builds. The Den app, in turn, stores each app's signing certificate with its approval: another app installed later under the same name is asked again.

## What it contains

1) `AndroidDeviceProbe`: SoC, SoC family, RAM and free RAM, thermal status;
2) the repository `recipes/`, bundled under `assets/den/recipes`;
3) `BuiltinCatalog`: Qwen3 0.6B and 1.7B in Q4_0 and Q4_K_M, with their Hugging Face URL and SHA-256;
4) the llama.cpp backend (CPU, and Adreno GPU through OpenCL);
5) the download of the chosen file on first use, checked against its SHA-256; `DenConfig.onDownload` reports its progress in both modes, and cancelling `model()` stops it (in connected mode, another app waiting for the same model then resumes it);
6) the AIDL client of the Den app.

## Memory in embedded mode

A model loaded in the app process is unloaded when it is idle and Android reports low memory, when the app moves to the background list (where Android kills processes first), and on `den.close()`, which closes every model the `Den` opened. A model that is generating is never unloaded. An unloaded model loads again on its next request. In connected mode, the Den app does the same for every app.

## Engine crashes

The backends run in a separate process of the app, `:den_engine` (Android 10 and later), declared by the SDK's manifest. A native crash, for example a GPU driver abort, ends that process and not the app:
- a crash while loading counts as a failed candidate, and the router loads the next one;
- a crash before the first word of a reply loads the next candidate and replays the request once; after the first word, the request ends with an error;
- crashed candidates are saved in `<filesDir>/den/failures.json` and skipped after a restart, until the system build or the backend version changes;
- a process stopped by the system (low memory) is not a crash: the model loads again on its next request.

The engine process creates each backend again from its class (a Kotlin object, a constructor taking a `Context`, or one without arguments), and runs the app's `Application.onCreate` like any process of the app. `DenConfig(isolate = false)` keeps the backends in the app process. The Den app runs its engine the same way, so a crash there does not stop the service.

## Model names

`den.model(name)` accepts:
- a built-in name (`qwen3-0.6b`, `qwen3-1.7b`), with files and checksums pinned in the SDK;
- a name from the curated index in `models/` (`qwen3-4b`), resolved through its source;
- any Hugging Face repository of GGUF files, `hf:owner/repo` or `hf:owner/repo@revision`.

`ModelScopeCatalog` (`ms:owner/repo`) exists but is not on by default: ModelScope refuses anonymous clients the file list (HTTP 403 from a phone, and a strict rate limit), so it needs an access token first (#90).

Hub names are looked up when the app asks for them: the file tree gives each file's size and SHA-256, downloads are pinned to the commit, and the result is cached for offline use. Without a recipe, Den prefers 4-bit files (Q4_K_M, then Q4_0) and never picks 1- or 2-bit files. Gated repositories are refused, since their terms must be accepted first. A file with the same SHA-256 as one already downloaded from another hub is used as it is, not downloaded again. When nothing fits, the error says why (unknown model, not enough free RAM).

## Your own parts

In embedded mode, the backend, the catalog, the file store and extra recipes can come from the app, through `DenConfig`; the defaults are llama.cpp, the built-in catalog, the downloader and the bundled recipes.

```kotlin
val den = Den.connect(context, DenConfig(
    mode = DenMode.EMBEDDED,
    backends = listOf(MyBackend),                  // implements DenBackend
    catalogs = listOf(MyCatalog, BuiltinCatalog),  // merged in order; the first that serves a file gives it
    recipes = listOf(myRecipe),                    // added to the bundled ones
))
```

The Den app uses its own parts, so these settings do not change connected mode. `apps/testapp/.../EchoParts.kt` is a complete example.

## Current limits

- Only the built-in catalog; the live catalog comes with #31.
- The Den app asks the user once per app, on its first `model()` call: the SDK opens the Den app's approval screen over the app (it must be visible; `DenConfig(approvalScreen = false)` turns this off), and a notification is posted as well. A denied app gets an error (in `AUTO`, it runs in the app process instead); an app whose request was closed without an answer is not asked again for 5 minutes.
