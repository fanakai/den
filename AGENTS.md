# AGENTS.md

Instructions for coding agents and contributors working on Fanak Den. Den runs open language models on the phone: an app names a model, and Den picks the backend, the accelerator and the model file for that phone, and shares one loaded copy between apps through the Den app.

## Layout

| Path | What it holds |
|---|---|
| `core/` | Router, backend interface, model files, recipes. Plain Kotlin (JVM), no Android dependency. |
| `backends/llamacpp/` | llama.cpp through JNI (`llama.cpp` is a git submodule), CPU and Adreno OpenCL. |
| `sdk/kotlin/` | Android SDK: device probe, built-in catalog, downloads, connected mode (AIDL client). |
| `apps/den/` | The Den app: AIDL service, model pool, app approvals, OpenAI-compatible HTTP endpoint. |
| `apps/testapp/`, `apps/demo/` | Test app (driven from adb) and a second app that uses only the SDK. |
| `recipes/` | Measured best choices per model and SoC, bundled into the SDK. See `recipes/README.md`. |

## Build and test

Requirements: JDK 17, Android SDK with platform 36, NDK `29.0.14206865` and CMake `3.22.1`.

```sh
git submodule update --init
./gradlew build                  # compile, unit tests and lint for every module, as CI does
./gradlew :core:test             # router and recipe tests only
./gradlew :apps:den:assembleRelease
```

- After a change to an AIDL file or another interface, run `./gradlew build --rerun-tasks` before opening a PR: an incremental build can miss a class that implements the interface.
- CI (`.github/workflows/build.yml`) runs `./gradlew build` on every pull request and must pass before merging.

## Testing on a phone

- Use release builds (`assembleRelease`, signed with the debug key). Debug builds compile the native code without optimization and decode about 3 times slower.
- Install with `adb install -r apps/<app>/build/outputs/apk/release/<app>-release.apk`, then read `adb logcat -s DenService:I DenTest:I DenDemo:I`.
- The test app is driven by intent extras, for example `am start -n ai.fanak.den.testapp/.MainActivity --es model qwen3-0.6b --es mode connected --ez autorun true`. See the comment at the top of each `MainActivity`.
- Keep the screen on while measuring: with the screen off or the app in the background, Android moves it to slower cores and the numbers drop.

## Rules

1) `core/` stays plain Kotlin, so the router can be reused on iOS and Linux boards later.
2) Never commit model files (`*.gguf`, `*.litertlm`, `*.pte`, `models/`); the model manager downloads them and checks their SHA-256.
3) A recipe holds only measured numbers, with the device, the date and the protocol. Never write an estimated or copied number into a recipe or a doc.
4) AIDL changes are added at the end of the interface, and `IDenService.version()` is raised, so older clients keep working.
5) A failing client must never take down the Den service: catch errors per client, and keep a reference to every binder that a death link is set on.
6) Comments only where the code does not say it already, and short.

## Workflow

1) One issue, one branch, one pull request; the PR description says `Closes #<issue>`.
2) Commit subjects are short, in the imperative, without a trailing full stop (`Add download progress over AIDL`).
3) A PR says what it changes, why, how it was tested (on which phone, with which numbers) and what it does not cover.
4) Planning and tracking are in GitHub issues and milestones (M1 to M5, then Future).
