# backends/litertlm

The LiteRT-LM backend: `.litertlm` files on the CPU (XNNPACK) and the GPU (OpenCL), through Google's runtime (`com.google.ai.edge.litertlm:litertlm-android` from Google Maven).

- **Version:** pinned to LiteRT-LM 0.20.0.
  - 0.18 and older cannot read the current Qwen3 files of `litert-community`, and the GPU path of their int4 file needs 0.19.
  - 0.20 is built for Java 25: the D8 of Android Gradle Plugin 8.10 rejects it, that of 8.13.2 reads it.
  - 0.19 and later are built with Kotlin 2.4, hence Kotlin 2.4 in this repository.
- **Size:** it adds `liblitertlm_jni.so` (22.7 MB, arm64-v8a) and its Kotlin code to an app.
- **NPU:** not offered. LiteRT-LM's Qualcomm NPU path needs the QAIRT libraries, whose licence is still open (#56).

## How a request runs

Den's requests carry the whole conversation; LiteRT-LM keeps one in a `Conversation`.
Each request creates a conversation with:
- the system messages as its system instruction;
- the earlier turns as its initial messages;

then sends the last user message and closes it. There is no KV reuse across requests.
One conversation runs at a time on a loaded model, so requests on it wait for each other.

- **Sampling:** temperature 0 is greedy (top-k 1); otherwise top-p with a top-k of 40.
- **Stop strings:** LiteRT-LM does not take them, so the backend scans the streamed text and cancels the generation at the first one.
- **Not supported:** grammars, JSON schemas and tools are refused (`UnsupportedFeatureException`).
- **Thinking:** a model's reasoning arrives on LiteRT-LM's `thought` channel; the backend passes it on in a leading `<think>` block, as llama.cpp gives it, and the HTTP API moves it to `reasoning_content`. Earlier replies sent back in the history lose their `<think>` block.
- **Numbers:** token counts and speeds come from `Conversation.getBenchmarkInfo()`, experimental in LiteRT-LM.

## Files

The built-in catalog has the int4 files (`dynamic_wi4b32_afp32`) of Qwen3-0.6B and Qwen3-1.7B from `litert-community` on Hugging Face, pinned to a commit.
Without a recipe, the router tries llama.cpp's GGUF files first: an unknown quant name ranks after the GGUF ones.
A recipe or `den bench` decides when LiteRT-LM goes first.

## Tests

`PartsTest` covers the parts that need no native code: stop strings, turns, sampling and stats.
LiteRT-LM's classes are Java 25 bytecode, so the JVM unit tests keep away from them.
The engine itself is tested on a phone with the test app (`--es quants int4`).
