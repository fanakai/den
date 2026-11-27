#!/bin/sh
# Builds the Hexagon NPU libraries for backends/llamacpp from the llama.cpp submodule: libggml-hexagon.so (host side,
# loaded at run time) and the HTP skels that run on the DSP, one per Hexagon version. It uses the public Snapdragon
# toolchain image (Hexagon SDK and Android NDK inside). Needs Docker; on Windows, run it in WSL.
#
#   scripts/build-hexagon.sh [out]        default out: backends/llamacpp/build/hexagon
#   ./gradlew :apps:den:assembleRelease -Pden.hexagon=backends/llamacpp/build/hexagon
#
# Skels from v73 (Snapdragon 8 Gen 2) up: v68 crashes the DSP on the A52s (#110), and a phone without a skel for its
# DSP keeps the CPU and GPU.
set -eu
IMAGE=ghcr.io/snapdragon-toolchain/arm64-android:v0.7
SKELS="v73 v75 v79 v81"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$ROOT/backends/llamacpp/build/hexagon}
# The cmake tree goes next to it: the Gradle build packs every folder in out as an ABI.
mkdir -p "$OUT/arm64-v8a" "$OUT.cmake"
OUT=$(cd "$OUT" && pwd)

# armv8.2 like the oldest CPU variant in the APK: this library is loaded on every phone, also where the NPU is unused.
FLAGS="-march=armv8.2-a+fp16+dotprod -D_GNU_SOURCE"
docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/den:ro" -v "$OUT:/out" -v "$OUT.cmake:/work" --platform linux/amd64 "$IMAGE" sh -c "
  set -e
  cmake -S /den/backends/llamacpp/llama.cpp -B /work -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=\$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DCMAKE_C_FLAGS='$FLAGS' -DCMAKE_CXX_FLAGS='$FLAGS' \
    -DHEXAGON_SDK_ROOT=\$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=\$HEXAGON_TOOLS_ROOT -DPREBUILT_LIB_DIR=android_aarch64 \
    -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_HEXAGON=ON -DGGML_HEXAGON_FP32_QUANTIZE_GROUP_SIZE=128 \
    -DGGML_OPENCL=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_COMMON=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF > /work/cmake.log
  cmake --build /work --target ggml-hexagon $(printf 'htp-%s ' $SKELS) > /work/build.log
  \$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip -o /out/arm64-v8a/libggml-hexagon.so \
    /work/bin/libggml-hexagon.so
  for v in $SKELS; do cp /work/ggml/src/ggml-hexagon/libggml-htp-\$v.so /out/arm64-v8a/; done
"
ls -l "$OUT/arm64-v8a"
