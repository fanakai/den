# AGENTS.md

Instructions for coding agents and contributors working on Fanak Den. Den runs open language models on the phone: an app names a model, and Den picks the backend, the accelerator and the model file for that phone, and shares one loaded copy between apps through the Den app.

## Layout

| Path | What it holds |
|---|---|
| `core/` | Router, backend interface, model files, recipes. Kotlin Multiplatform (JVM and `linuxX64`), no Android dependency; files and network in `jvmMain`. |
| `service/` | The OpenAI-compatible HTTP server, plain Kotlin (JVM), tested with the OpenAI Java client. |
| `backends/llamacpp/` | llama.cpp through JNI (`llama.cpp` is a git submodule), CPU and Adreno OpenCL. |
| `backends/openai/` | OpenAI-compatible servers on the LAN or in the cloud, plain Kotlin (JVM), tested against `service/`. |
| `sdk/kotlin/` | Android SDK: device probe, built-in catalog, downloads, connected mode (AIDL client). No backend: an app adds `backends/llamacpp` for embedded mode. |
| `apps/den/` | The Den app: AIDL service, model pool, app approvals, OpenAI-compatible HTTP endpoint. |
| `apps/testapp/`, `apps/demo/` | Test app (driven from adb) and a second app that uses only the SDK. |
| `recipes/` | Measured best choices per SoC by model size, bundled into the SDK. See `recipes/README.md`. |

## Build and test

Requirements: JDK 17, Android SDK with platform 36, NDK `29.0.14206865` and CMake `3.22.1`.

```sh
git submodule update --init
./gradlew build                  # compile, unit tests and lint for every module, as CI does
./gradlew :core:jvmTest          # router and recipe tests only (CI also runs them on linuxX64)
./gradlew :apps:den:assembleRelease
```

- After a change to an AIDL file or another interface, run `./gradlew build --rerun-tasks` before opening a PR: an incremental build can miss a class that implements the interface.
- The public API of `core/`, `sdk/kotlin/`, `backends/llamacpp/` and `backends/openai/` is in their `api/*.api` files, and `./gradlew build` fails when it changes. After an intended change, run `./gradlew apiDump` and commit the updated files with it.
- The recipe table in `README.md` and in `recipes/README.md` is made from the recipe files, and `./gradlew build` fails when either differs. After a recipe change, run `./gradlew :core:jvmTest -Pden.writeTable` and commit both READMEs with it.
- CI (`.github/workflows/build.yml`) runs `./gradlew build` on every pull request and must pass before merging.
- A tag `v<major>.<minor>.<patch>` publishes the Den app on GitHub Releases and the libraries on Maven Central ([docs/releasing.md](docs/releasing.md)); `publishToMavenLocal` checks the libraries before.
- On Linux and macOS, `./gradlew :backends:llamacpp:test` also builds the engine for the machine (cmake and a C++ compiler needed) and runs it through JNI on a 1.2 MB model: generation, stop strings, cancel, context limits. On Windows these tests are skipped; CI runs them.

## Testing on a phone

- Use release builds (`assembleRelease`). The Den app is signed with the release key when `~/.gradle/gradle.properties` names it (`den.release.storeFile`, `den.release.keyAlias`, `den.release.passwordFile`), and with the debug key otherwise; `-Pden.release.storeFile=` forces the debug key, for a phone that has a debug-signed Den app. The key never goes into the repository. Debug builds compile the native code without optimization and decode about 3 times slower.
- Install with `adb install -r apps/<app>/build/outputs/apk/release/<app>-release.apk`, then read `adb logcat -s DenService:I DenTest:I DenDemo:I`.
- The test app is driven by intent extras, for example `am start -n ai.fanak.den.testapp/.MainActivity --es model qwen3-0.6b --es mode connected --ez autorun true`. See the comment at the top of each `MainActivity`.
- Keep the screen on while measuring: with the screen off or the app in the background, Android moves it to slower cores and the numbers drop.

## Rules

1) `core/` stays multiplatform: `commonMain` uses no `java.*`, so the router can be reused on iOS and Linux boards later.
2) Never commit model files (`*.gguf`, `*.litertlm`, `*.pte`, `models/`); the model manager downloads them and checks their SHA-256.
3) A recipe holds only measured numbers, with the device, the date and the protocol. Never write an estimated or copied number into a recipe or a doc.
4) AIDL changes are added at the end of the interface, and `IDenService.version()` is raised, so older clients keep working ([docs/aidl.md](docs/aidl.md)). The HTTP API keeps `/v1` to what OpenAI has, and puts Den's own calls under `/den/v1` ([docs/http-api.md](docs/http-api.md)).
5) A failing client must never take down the Den service: catch errors per client, and keep a reference to every binder that a death link is set on.
6) Comments only where the code does not say it already, and short.

## Workflow

1) One issue, one branch, one pull request; the PR description says `Closes #<issue>`.
2) Commit subjects are short, in the imperative, without a trailing full stop (`Add download progress over AIDL`).
3) A PR says what it changes, why, how it was tested (on which phone, with which numbers) and what it does not cover.
4) Planning and tracking are in GitHub issues and milestones (M1 to M5, then Future).
