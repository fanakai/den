# backends/llamacpp

The llama.cpp backend (GGUF) through JNI, on the CPU and on Adreno GPUs through OpenCL.

It has three parts:

1) `llama.cpp/`: git submodule pinned to release `b9590`;
2) `src/main/cpp/`: `engine.cpp` wraps llama.cpp (chat template, prefill, sampling, stop strings), `threads.cpp` picks the thread counts and `jni.cpp` exposes it to Kotlin;
3) `LlamaCppBackend`: implements `DenBackend` from `core`.

The ggml backends are separate libraries loaded at run time (`GGML_BACKEND_DL`).
On a phone whose OpenCL driver is old or missing, `libggml-opencl.so` fails to load and the CPU is still used.
The OpenCL headers and a link-time `libOpenCL.so` come from the Khronos repositories at configure time; that stub is not packaged, since the vendor driver must be the one loaded.

## Build

```
git submodule update --init
./gradlew :backends:llamacpp:assembleRelease
```

Requires Android SDK platform 36, NDK 29.0.14206865, CMake 3.22.1 and Python 3 (to embed the OpenCL kernels).

## Smoke test without an app

`smoke.cpp` builds a standalone `den-smoke` binary on the same engine, which runs in Termux:

```
cmake -S src/main/cpp -B build-smoke -G Ninja -DDEN_SMOKE=ON -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared
ninja -C build-smoke den-smoke ggml-cpu ggml-opencl
```

Copy `den-smoke`, `bin/*.so` and the NDK `libc++_shared.so` into one folder on the phone.
Termux cannot load the vendor OpenCL driver from `/vendor`, so copy `/vendor/lib64/libOpenCL*.so` into a folder of its own first:

```
LD_LIBRARY_PATH=$HOME/den/dl:$HOME/den/ocl ./den-smoke Qwen3-0.6B-Q4_0.gguf 0 99 long
```

Arguments: model, threads (0 = automatic), GPU layers (0 = CPU), prompt (`long` = about 300 tokens).
