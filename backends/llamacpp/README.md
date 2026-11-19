# backends/llamacpp

The llama.cpp backend (GGUF) through JNI. CPU only for now; the GPU paths come with #4.

It has three parts:

1) `llama.cpp/`: git submodule pinned to release `b9590`;
2) `src/main/cpp/`: `engine.cpp` wraps llama.cpp (chat template, prefill, sampling, stop strings) and `jni.cpp` exposes it to Kotlin;
3) `LlamaCppBackend`: implements `DenBackend` from `core`.

## Build

```
git submodule update --init
./gradlew :backends:llamacpp:assembleRelease
```

Requires Android SDK platform 36, NDK 29.0.14206865 and CMake 3.22.1.

## Smoke test without an app

`smoke.cpp` builds a standalone `den-smoke` binary on the same engine, which runs in Termux:

```
cmake -S src/main/cpp -B build-smoke -G Ninja -DDEN_SMOKE=ON -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static
ninja -C build-smoke den-smoke
# on the phone
./den-smoke Qwen3-0.6B-Q4_K_M.gguf 4
```
